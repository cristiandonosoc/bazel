// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.actions.ActionAnalysisMetadata.mergeMaps;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineAndParamFileInfo;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.ResourceSetOrBuilder;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.starlark.Args;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.exec.SpawnStrategyResolver;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext.Linkstamp;
import com.google.devtools.build.lib.rules.cpp.LinkerInputs.LibraryToLink;
import com.google.devtools.build.lib.server.FailureDetails.CppLink;
import com.google.devtools.build.lib.server.FailureDetails.CppLink.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.starlarkbuildapi.CommandLineArgsApi;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkList;

/** Action that represents a linking step. */
@ThreadCompatible
public final class CppLinkAction extends AbstractAction implements CommandAction {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * An abstraction for creating intermediate and output artifacts for C++ linking.
   *
   * <p>This is unfortunately necessary, because most of the time, these artifacts are well-behaved
   * ones sitting under a package directory, but nativedeps link actions can be shared. In order to
   * avoid creating every artifact here with {@code getShareableArtifact()}, we abstract the
   * artifact creation away.
   */
  public interface LinkArtifactFactory {
    /** Create an artifact at the specified root-relative path in the bin directory. */
    Artifact create(
        ActionConstructionContext actionConstructionContext,
        RepositoryName repositoryName,
        BuildConfigurationValue configuration,
        PathFragment rootRelativePath);

    /** Create a tree artifact at the specified root-relative path in the bin directory. */
    SpecialArtifact createTreeArtifact(
        ActionConstructionContext actionConstructionContext,
        RepositoryName repositoryName,
        BuildConfigurationValue configuration,
        PathFragment rootRelativePath);
  }

  /**
   * An implementation of {@link LinkArtifactFactory} that can only create artifacts in the package
   * directory.
   */
  public static final LinkArtifactFactory DEFAULT_ARTIFACT_FACTORY =
      new LinkArtifactFactory() {
        @Override
        public Artifact create(
            ActionConstructionContext actionConstructionContext,
            RepositoryName repositoryName,
            BuildConfigurationValue configuration,
            PathFragment rootRelativePath) {
          return actionConstructionContext.getDerivedArtifact(
              rootRelativePath, configuration.getBinDirectory(repositoryName));
        }

        @Override
        public SpecialArtifact createTreeArtifact(
            ActionConstructionContext actionConstructionContext,
            RepositoryName repositoryName,
            BuildConfigurationValue configuration,
            PathFragment rootRelativePath) {
          return actionConstructionContext.getTreeArtifact(
              rootRelativePath, configuration.getBinDirectory(repositoryName));
        }
      };

  /**
   * An implementation of {@link LinkArtifactFactory} that can create artifacts anywhere.
   *
   * <p>Necessary when the LTO backend actions of libraries should be shareable, and thus cannot be
   * under the package directory.
   *
   * <p>Necessary because the actions of nativedeps libraries should be shareable, and thus cannot
   * be under the package directory.
   */
  public static final LinkArtifactFactory SHAREABLE_LINK_ARTIFACT_FACTORY =
      new LinkArtifactFactory() {
        @Override
        public Artifact create(
            ActionConstructionContext actionConstructionContext,
            RepositoryName repositoryName,
            BuildConfigurationValue configuration,
            PathFragment rootRelativePath) {
          return actionConstructionContext.getShareableArtifact(
              rootRelativePath, configuration.getBinDirectory(repositoryName));
        }

        @Override
        public SpecialArtifact createTreeArtifact(
            ActionConstructionContext actionConstructionContext,
            RepositoryName repositoryName,
            BuildConfigurationValue configuration,
            PathFragment rootRelativePath) {
          return actionConstructionContext
              .getAnalysisEnvironment()
              .getTreeArtifact(rootRelativePath, configuration.getBinDirectory(repositoryName));
        }
      };

  private static final String LINK_GUID = "58ec78bd-1176-4e36-8143-439f656b181d";

  @Nullable private final String mnemonic;
  private final LibraryToLink outputLibrary;
  private final Artifact linkOutput;
  private final LibraryToLink interfaceOutputLibrary;
  private final ImmutableMap<String, String> toolchainEnv;
  private final ImmutableMap<String, String> executionRequirements;
  private final ImmutableMap<Linkstamp, Artifact> linkstamps;

  private final LinkCommandLine linkCommandLine;
  private final ActionEnvironment env;

  private final boolean isLtoIndexing;

  private final String ldExecutable;

  /**
   * Use {@link CppLinkActionBuilder} to create instances of this class. Also see there for the
   * documentation of all parameters.
   *
   * <p>This constructor is intentionally private and is only to be called from {@link
   * CppLinkActionBuilder#build()}.
   */
  CppLinkAction(
      ActionOwner owner,
      String mnemonic,
      NestedSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs,
      LibraryToLink outputLibrary,
      Artifact linkOutput,
      LibraryToLink interfaceOutputLibrary,
      boolean isLtoIndexing,
      ImmutableMap<Linkstamp, Artifact> linkstamps,
      LinkCommandLine linkCommandLine,
      ActionEnvironment env,
      ImmutableMap<String, String> toolchainEnv,
      ImmutableMap<String, String> executionRequirements,
      String ldExecutable) {
    super(owner, inputs, outputs);
    this.mnemonic = getMnemonic(mnemonic, isLtoIndexing);
    this.outputLibrary = outputLibrary;
    this.linkOutput = linkOutput;
    this.interfaceOutputLibrary = interfaceOutputLibrary;
    this.isLtoIndexing = isLtoIndexing;
    this.linkstamps = linkstamps;
    this.linkCommandLine = linkCommandLine;
    this.env = env;
    this.toolchainEnv = toolchainEnv;
    this.executionRequirements = executionRequirements;
    this.ldExecutable = ldExecutable;
  }

  @Override
  @VisibleForTesting
  public NestedSet<Artifact> getPossibleInputsForTesting() {
    return getInputs();
  }

  @Override
  public ActionEnvironment getEnvironment() {
    return env;
  }

  @Override
  @VisibleForTesting
  public ImmutableMap<String, String> getIncompleteEnvironmentForTesting() {
    return getEffectiveEnvironment(ImmutableMap.of());
  }

  @Override
  public ImmutableMap<String, String> getEffectiveEnvironment(Map<String, String> clientEnv) {
    LinkedHashMap<String, String> result =
        Maps.newLinkedHashMapWithExpectedSize(env.estimatedSize());
    env.resolve(result, clientEnv);

    result.putAll(toolchainEnv);

    if (!executionRequirements.containsKey(ExecutionRequirements.REQUIRES_DARWIN)) {
      // This prevents gcc from writing the unpredictable (and often irrelevant)
      // value of getcwd() into the debug info.
      result.put("PWD", "/proc/self/cwd");
    }
    return ImmutableMap.copyOf(result);
  }

  @VisibleForTesting
  public LinkCommandLine getLinkCommandLineForTesting() {
    return linkCommandLine;
  }

  /**
   * Returns the output of this action as a {@link LibraryToLink} or null if it is an executable.
   */
  @Nullable
  LibraryToLink getOutputLibrary() {
    return outputLibrary;
  }

  LibraryToLink getInterfaceOutputLibrary() {
    return interfaceOutputLibrary;
  }

  @Override
  public ImmutableMap<String, String> getExecutionInfo() {
    return mergeMaps(super.getExecutionInfo(), executionRequirements);
  }

  @Override
  public Sequence<CommandLineArgsApi> getStarlarkArgs() {
    ImmutableSet<Artifact> directoryInputs =
        getInputs().toList().stream()
            .filter(Artifact::isDirectory)
            .collect(ImmutableSet.toImmutableSet());

    CommandLine commandLine = linkCommandLine.getCommandLineForStarlark();

    CommandLineAndParamFileInfo commandLineAndParamFileInfo =
        new CommandLineAndParamFileInfo(commandLine, /* paramFileInfo= */ null);

    Args args = Args.forRegisteredAction(commandLineAndParamFileInfo, directoryInputs);

    return StarlarkList.immutableCopyOf(ImmutableList.of(args));
  }

  @Override
  public List<String> getArguments() throws CommandLineExpansionException {
    return linkCommandLine.arguments();
  }

  /**
   * Returns the command line specification for this link, included any required linkstamp
   * compilation steps. The command line may refer to a .params file.
   *
   * @param expander ArtifactExpander for expanding TreeArtifacts.
   * @return a finalized command line suitable for execution
   */
  public List<String> getCommandLine(@Nullable ArtifactExpander expander)
      throws CommandLineExpansionException {
    return linkCommandLine.getCommandLine(expander);
  }

  /**
   * Returns a (possibly empty) list of linkstamp object files.
   *
   * <p>This is used to embed various values from the build system into binaries to identify their
   * provenance.
   */
  ImmutableList<Artifact> getLinkstampObjects() {
    return linkstamps.keySet().stream()
        .map(CcLinkingContext.Linkstamp::getArtifact)
        .collect(ImmutableList.toImmutableList());
  }

  ImmutableCollection<Artifact> getLinkstampObjectFileInputs() {
    return linkstamps.values();
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    ResourceSetOrBuilder localResources = new LinkResourceSetBuilder();

    try {
      int inputsCount = linkCommandLine.getLinkerInputArtifacts().memoizedFlattenAndGetSize();
      ResourceSet resourceSet = localResources.buildResourceSet(OS.getCurrent(), inputsCount);
      Spawn spawn = createSpawn(actionExecutionContext, resourceSet);
      ImmutableList<SpawnResult> spawnResults =
          actionExecutionContext
              .getContext(SpawnStrategyResolver.class)
              .exec(spawn, actionExecutionContext);

      // As per the documentation of SpawnStrategy.exec(), the first entry is always the result of
      // the successful execution
      SpawnResult result = spawnResults.get(0);
      Long consumedMemoryInKb = result.getMemoryInKb();
      if (consumedMemoryInKb != null) {
        logger.atFine().log(
            "Linker metrics: inputs_count=%d,inputs_mb=%.2f,estimated_mb=%.2f,consumed_mb=%.2f",
            inputsCount,
            0d, // Because inputs sizes weren't used, we don't compute them
            resourceSet.getMemoryMb(),
            ((double) consumedMemoryInKb) / 1024);
      }

      return ActionResult.create(spawnResults);
    } catch (ExecException e) {
      throw ActionExecutionException.fromExecException(e, CppLinkAction.this);
    }
  }

  private Spawn createSpawn(
      ActionExecutionContext actionExecutionContext, ResourceSet localResources)
      throws ActionExecutionException {
    try {
      ArtifactExpander actionContextExpander = actionExecutionContext.getArtifactExpander();
      ArtifactExpander expander = actionContextExpander;
      return new SimpleSpawn(
          this,
          ImmutableList.copyOf(getCommandLine(expander)),
          getEffectiveEnvironment(actionExecutionContext.getClientEnv()),
          getExecutionInfo(),
          getInputs(),
          getOutputs(),
          localResources);
    } catch (CommandLineExpansionException e) {
      String message =
          String.format(
              "failed to generate link command for rule '%s: %s",
              getOwner().getLabel(), e.getMessage());
      DetailedExitCode code = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE);
      throw new ActionExecutionException(message, this, /*catastrophe=*/ false, code);
    }
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable Artifact.ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException {
    fp.addString(LINK_GUID);
    fp.addString(ldExecutable);
    fp.addStrings(linkCommandLine.arguments());
    fp.addStringMap(toolchainEnv);
    fp.addStrings(getExecutionInfo().keySet());

    // TODO(bazel-team): For correctness, we need to ensure the invariant that all values accessed
    // during the execution phase are also covered by the key. Above, we add the argv to the key,
    // which covers most cases. Unfortunately, the extra action method above also
    // sometimes directly accesses settings from the link configuration that may or may not affect
    // the
    // key. We either need to change the code to cover them in the key computation, or change the
    // LinkConfiguration to disallow the combinations where the value of a setting does not affect
    // the argv.
    fp.addBoolean(linkCommandLine.isNativeDeps());
    fp.addBoolean(linkCommandLine.useTestOnlyFlags());
    if (linkCommandLine.getToolchainLibrariesSolibDir() != null) {
      fp.addPath(linkCommandLine.getToolchainLibrariesSolibDir());
    }
    fp.addBoolean(isLtoIndexing);
  }

  @Override
  public String describeKey() {
    StringBuilder message = new StringBuilder();
    message.append(getProgressMessage());
    message.append('\n');
    message.append("  Command: ");
    message.append(ShellEscaper.escapeString(linkCommandLine.getLinkerPathString()));
    message.append('\n');
    // Outputting one argument per line makes it easier to diff the results.
    try {
      List<String> arguments = linkCommandLine.arguments();
      for (String argument : ShellEscaper.escapeAll(arguments)) {
        message.append("  Argument: ");
        message.append(argument);
        message.append('\n');
      }
    } catch (CommandLineExpansionException e) {
      message.append("  Could not expand command line: ");
      message.append(e);
      message.append('\n');
    }
    return message.toString();
  }

  @Override
  public String getMnemonic() {
    return mnemonic;
  }

  static String getMnemonic(String mnemonic, boolean isLtoIndexing) {
    if (mnemonic == null) {
      return isLtoIndexing ? "CppLTOIndexing" : "CppLink";
    }
    return mnemonic;
  }

  @Override
  protected String getRawProgressMessage() {
    return (isLtoIndexing ? "LTO indexing " : "Linking ") + linkOutput.prettyPrint();
  }

  /** Estimates resource consumption when this action is executed locally. */
  @VisibleForTesting
  static class LinkResourceSetBuilder implements ResourceSetOrBuilder {
    @Override
    public ResourceSet buildResourceSet(OS os, int inputsCount) throws ExecException {

      final ResourceSet resourceSet;
      switch (os) {
        case DARWIN:
          resourceSet =
              ResourceSet.createWithRamCpu(/* memoryMb= */ 15 + 0.05 * inputsCount, /* cpu= */ 1);
          break;
        case LINUX:
          resourceSet =
              ResourceSet.createWithRamCpu(
                  /* memoryMb= */ Math.max(50, -100 + 0.1 * inputsCount), /* cpu= */ 1);
          break;
        default:
          resourceSet =
              ResourceSet.createWithRamCpu(/* memoryMb= */ 1500 + inputsCount, /* cpu= */ 1);
          break;
      }
      return resourceSet;
    }
  }

  @Override
  public Sequence<String> getStarlarkArgv() throws EvalException {
    try {
      return StarlarkList.immutableCopyOf(getArguments());
    } catch (CommandLineExpansionException ex) {
      throw new EvalException(ex);
    }
  }

  private static DetailedExitCode createDetailedExitCode(String message, Code detailedCode) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setCppLink(CppLink.newBuilder().setCode(detailedCode))
            .build());
  }
}
