package org.technologybrewery.habushu;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.FileConfig;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.checkerframework.checker.units.qual.A;
import org.technologybrewery.habushu.exec.PoetryCommandHelper;
import org.technologybrewery.habushu.util.HabushuUtil;
import org.technologybrewery.habushu.util.TomlReplacementTuple;
import org.technologybrewery.habushu.util.TomlUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Installs dependencies defined in the project's pyproject.toml configuration,
 * specifically by running "poetry lock" followed by "poetry install". If a
 * private PyPi repository is defined via
 * {@link AbstractHabushuMojo#pypiRepoUrl} (and
 * {@link AbstractHabushuMojo#pypiRepoId}), it will be automatically added to
 * the module's pyproject.toml configuration as a supplemental source of
 * dependencies, if it is not already configured in the pyproject.toml
 */
@Mojo(name = "install-dependencies", defaultPhase = LifecyclePhase.COMPILE)
public class InstallDependenciesMojo extends AbstractHabushuMojo {

    private static final String EQUALS = "=";
    private static final String DOUBLE_QUOTE = "\"";

    /**
     * Configures whether a private PyPi repository, if specified via
     * {@link AbstractHabushuMojo#pypiRepoUrl}, is automatically added as a package
     * source from which dependencies may be installed. This value is <b>*only*</b>
     * utilized if a private PyPi repository is specified via
     * {@link AbstractHabushuMojo#pypiRepoUrl}.
     */
    @Parameter(defaultValue = "true", property = "habushu.addPypiRepoAsPackageSources")
    private boolean addPypiRepoAsPackageSources;

    /**
     * Configures the path for the simple index on a private pypi repository.
     * Certain private repository solutions (ie: devpi) use different names for the
     * simple index. devpi, for instance, uses "+simple".
     */
    @Parameter(property = "habushu.pypiSimpleSuffix", defaultValue = "simple")
    private String pypiSimpleSuffix;

    /**
     * Configures whether the poetry lock file will be updated before poetry
     * install.
     */
    @Parameter(defaultValue = "false", property = "habushu.skipPoetryLockUpdate")
    private boolean skipPoetryLockUpdate;

    /**
     * Path within a Poetry project's pyproject.toml configuration at which private
     * PyPi repositories may be specified as sources from which dependencies may be
     * resolved and installed.
     */
    protected static final String PYPROJECT_PACKAGE_SOURCES_PATH = "tool.poetry.source";

    /**
     * Specifies Poetry groups to include in the installation.
     */
    @Parameter(property = "habushu.withGroups")
    private String[] withGroups;

    /**
     * Specifies Poetry groups to exclude from the installation.
     */
    @Parameter(property = "habushu.withoutGroups")
    private String[] withoutGroups;

    /**
     * Configuration option to include the --sync option on poetry install
     */
    @Parameter(defaultValue = "false", property = "habushu.forceSync")
    private boolean forceSync;

    /**
     * The set of managed dependencies to monitor for conformance.  These can result in:
     * * direct changes to your pyproject.toml file (default behavior)
     * * log statements warning of mismatches (if habushu.updateManagedDependenciesWhenFound = false)
     * * stopping the build for manual intervention (if habushu.failOnManagedDependenciesMismatches = true)
     */
    @Parameter(property = "habushu.managedDependencies")
    protected List<PackageDefinition> managedDependencies;

    /**
     * Determines if managed dependency mismatches are automatically updated when encountered.
     */
    @Parameter(defaultValue = "true", property = "habushu.updateManagedDependenciesWhenFound")
    protected boolean updateManagedDependenciesWhenFound;

    /**
     * Determines if the build should be failed when managed dependency mismatches are found.
     */
    @Parameter(defaultValue = "false", property = "habushu.failOnManagedDependenciesMismatches")
    protected boolean failOnManagedDependenciesMismatches;

    /**
     * Whether to configure Poetry's {@code virtualenvs.in-project} value for this project.
     * If configured, virtual environments will be migrated to this approach during the clean phase of the build.
     *
     * While generally easier to find and use for tasks like debugging, having your virtual environment co-located in
     * your project may be less useful for executions like CI builds where you may want to centrally caches virtual
     * environments from a central location.
     */
    @Parameter(defaultValue = "true", property = "habushu.useInProjectVirtualEnvironment")
    protected boolean useInProjectVirtualEnvironment;

    @Override
    public void doExecute() throws MojoExecutionException, MojoFailureException {
        PoetryCommandHelper poetryHelper = createPoetryCommandHelper();

        setUpInProjectVirtualEnvironment(poetryHelper);

        processManagedDependencyMismatches();

        prepareRepositoryForInstallation(this.pypiRepoId, this.pypiRepoUrl);
        if (this.useDevRepository) {
            prepareRepositoryForInstallation(this.devRepositoryId, this.devRepositoryUrl);
        }

        if (!this.skipPoetryLockUpdate) {
            getLog().info("Locking dependencies specified in pyproject.toml...");
            poetryHelper.executePoetryCommandAndLogAfterTimeout(Arrays.asList("lock"), 2, TimeUnit.MINUTES);
        }

        List<String> installCommand = new ArrayList<>();

        installCommand.add("install");
        for (String groupName : this.withGroups) {
            installCommand.add("--with");
            installCommand.add(groupName);
        }
        for (String groupName : this.withoutGroups) {
            installCommand.add("--without");
            installCommand.add(groupName);
        }
        if (this.forceSync) {
            installCommand.add("--sync");
        }

        getLog().info("Installing dependencies...");
        poetryHelper.executePoetryCommandAndLogAfterTimeout(installCommand, 2, TimeUnit.MINUTES);
    }

    private void setUpInProjectVirtualEnvironment(PoetryCommandHelper poetryHelper) throws MojoExecutionException {
        String inProjectVirtualEnvironmentPath = HabushuUtil.getInProjectVirtualEnvironmentPath(getPoetryProjectBaseDir());
        File venv = new File(inProjectVirtualEnvironmentPath);
        if (this.useInProjectVirtualEnvironment) {
            if (!venv.exists()) {
                getLog().info("Configuring Poetry to use an in-project virtual environment...");
                configureVirtualEnvironmentsInProject(true);
            }
        } else {
            List<String> arguments = new ArrayList<>();
            arguments.add("config");
            arguments.add("virtualenvs.in-project");
            arguments.add("--local");
            String currentInProjectSetting = poetryHelper.execute(arguments);

            if (Boolean.TRUE.equals(Boolean.valueOf(currentInProjectSetting))) {
                configureVirtualEnvironmentsInProject(false);
            }
        }
    }

    private void configureVirtualEnvironmentsInProject(boolean enable) throws MojoExecutionException {
        PoetryCommandHelper poetryHelper = createPoetryCommandHelper();
        List<String> arguments = new ArrayList<>();
        arguments.add("config");
        arguments.add("virtualenvs.in-project");
        arguments.add(Boolean.toString(enable));
        arguments.add("--local");
        poetryHelper.executeAndLogOutput(arguments);
    }

    private void prepareRepositoryForInstallation(String repoId, String repoUrl) throws MojoExecutionException {
        if (StringUtils.isNotEmpty(repoUrl) && this.addPypiRepoAsPackageSources) {
            String pypiRepoSimpleIndexUrl;
            try {
                pypiRepoSimpleIndexUrl = getPyPiRepoSimpleIndexUrl(repoUrl);
            } catch (URISyntaxException e) {
                throw new MojoExecutionException(
                        String.format("Could not parse configured repoUrl %s", repoUrl), e);
            }

            // NB later version of Poetry will support retrieving and configuring package
            // source repositories via the "poetry source" command in future releases, but
            // for now we need to manually inspect and modify the package's pyproject.toml
            Config matchingPypiRepoSourceConfig;
            try (FileConfig pyProjectConfig = FileConfig.of(getPoetryPyProjectTomlFile())) {
                pyProjectConfig.load();

                Optional<List<Config>> packageSources = pyProjectConfig.getOptional(PYPROJECT_PACKAGE_SOURCES_PATH);
                matchingPypiRepoSourceConfig = packageSources.orElse(Collections.emptyList()).stream()
                        .filter(packageSource -> pypiRepoSimpleIndexUrl.equals(packageSource.get("url"))).findFirst()
                        .orElse(Config.inMemory());
            }

            if (!matchingPypiRepoSourceConfig.isEmpty()) {
                if (getLog().isDebugEnabled()) {
                    getLog().debug(String.format(
                            "Configured PyPi repository %s found in the following pyproject.toml [[%s]] array element: %s",
                            repoUrl, PYPROJECT_PACKAGE_SOURCES_PATH, matchingPypiRepoSourceConfig));
                }
            } else {
                // NB NightConfig's TOML serializer generates TOML in a manner that makes it
                // difficult to append an array element of tables to an existing TOML
                // configuration, so manually write out the desired new repository TOML
                // configuration with human-readable formatting
                List<String> newPypiRepoSourceConfig = Arrays.asList(System.lineSeparator(), String.format(
                                "# Added by habushu-maven-plugin at %s to use %s as source PyPi repository for installing dependencies",
                                LocalDateTime.now(), pypiRepoSimpleIndexUrl),
                        String.format("[[%s]]", PYPROJECT_PACKAGE_SOURCES_PATH),
                        String.format("name = \"%s\"",
                                StringUtils.isNotEmpty(repoId) && !PUBLIC_PYPI_REPO_ID.equals(repoId)
                                        ? repoId
                                        : "private-pypi-repo"),
                        String.format("url = \"%s\"", pypiRepoSimpleIndexUrl), "priority = \"supplemental\"");
                getLog().info(String.format("Private PyPi repository entry for %s not found in pyproject.toml",
                        repoUrl));
                getLog().info(String.format(
                        "Adding %s to pyproject.toml as supplemental repository from which dependencies may be installed",
                        pypiRepoSimpleIndexUrl));
                try {
                    Files.write(getPoetryPyProjectTomlFile().toPath(), newPypiRepoSourceConfig,
                            StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new MojoExecutionException(String.format(
                            "Could not write new [[%s]] element to pyproject.toml", PYPROJECT_PACKAGE_SOURCES_PATH), e);
                }
            }

        }
    }

    /**
     * Attempts to infer the PEP-503 compliant PyPI simple repository index URL
     * associated with the provided PyPI repository URL. In order to configure
     * Poetry to use a private PyPi repository as a source for installing package
     * dependencies, the simple index URL of the repository <b>*must*</b> be
     * utilized. For example, if a private PyPI repository is hosted at
     * https://my-company-sonatype-nexus/repository/internal-pypi and provided to
     * Habushu via the {@literal <pypiRepoUrl>} configuration, the simple index URL
     * returned by this method will be
     * https://my-company-sonatype-nexus/repository/internal-pypi/simple/ (the
     * trailing slash is required!).
     *
     * @param pypiRepoUrl URL of the private PyPi repository for which to generate
     *                    the simple index API URL.
     * @return simple index API URL associated with the given PyPi repository URL.
     * @throws URISyntaxException
     */
    protected String getPyPiRepoSimpleIndexUrl(String pypiRepoUrl) throws URISyntaxException {
        URIBuilder pypiRepoUriBuilder = new URIBuilder(StringUtils.removeEnd(pypiRepoUrl, "/"));
        List<String> repoUriPathSegments = pypiRepoUriBuilder.getPathSegments();
        String lastPathSegment = CollectionUtils.isNotEmpty(repoUriPathSegments)
                ? repoUriPathSegments.get(repoUriPathSegments.size() - 1)
                : null;
        if (!this.pypiSimpleSuffix.equals(lastPathSegment)) {
            // If the URL has no path, an unmodifiable Collections.emptyList() is returned,
            // so wrap in an ArrayList to enable later modifications
            repoUriPathSegments = new ArrayList<>(repoUriPathSegments);
            repoUriPathSegments.add(this.pypiSimpleSuffix);
            pypiRepoUriBuilder.setPathSegments(repoUriPathSegments);
        }

        return StringUtils.appendIfMissing(pypiRepoUriBuilder.build().toString(), "/");
    }

    protected void processManagedDependencyMismatches() {
        if (!managedDependencies.isEmpty()) {
            Map<String, TomlReplacementTuple> replacements = new HashMap<>();

            try (FileConfig pyProjectConfig = FileConfig.of(getPoetryPyProjectTomlFile())) {
                pyProjectConfig.load();

                // Look for the standard Poetry dependency groups:
                executeDetailedManagedDependencyMismatchActions(replacements, pyProjectConfig, "tool.poetry.dependencies");
                executeDetailedManagedDependencyMismatchActions(replacements, pyProjectConfig, "tool.poetry.dev-dependencies");

                // Search for custom Poetry dependency groups:
                List<String> toolPoetryGroupSections = findCustomToolPoetryGroups();
                for (String toolPoetryGroupSection : toolPoetryGroupSections) {
                    executeDetailedManagedDependencyMismatchActions(replacements, pyProjectConfig, toolPoetryGroupSection);
                }

                // Log replacements, if appropriate:
                if (failOnManagedDependenciesMismatches || !updateManagedDependenciesWhenFound) {
                    for (TomlReplacementTuple replacement : replacements.values()) {
                        logPackageMismatch(replacement.getPackageName(), replacement.getOriginalOperatorAndVersion(),
                                replacement.getUpdatedOperatorAndVersion());
                    }
                }

                performPendingDependencyReplacements(replacements);
            }
        }
    }

    private void executeDetailedManagedDependencyMismatchActions(Map<String, TomlReplacementTuple> replacements,
                                                                 FileConfig pyProjectConfig, String tomlSection) {

        Optional<Config> toolPoetryDependencies = pyProjectConfig.getOptional(tomlSection);
        if (toolPoetryDependencies.isPresent()) {
            Config foundDependencies = toolPoetryDependencies.get();
            Map<String, Object> dependencyMap = foundDependencies.valueMap();

            for (PackageDefinition def : managedDependencies) {
                String packageName = def.getPackageName();
                if (dependencyMap.containsKey(packageName)) {
                    Object packageRhs = dependencyMap.get(packageName);

                    if (TomlUtils.representsLocalDevelopmentVersion(packageRhs)) {
                        getLog().info(String.format("%s does not have a specific version to manage - skipping", packageName));
                        getLog().debug(String.format("\t %s", packageRhs.toString()));
                        continue;
                    }

                    performComparisonAndStageNeededChanges(replacements, def, packageRhs);
                }
            }
        }
    }

    private void performComparisonAndStageNeededChanges(Map<String, TomlReplacementTuple> replacements, PackageDefinition def, Object packageRhs) {
        String originalOperatorAndVersion = getOperatorAndVersion(packageRhs);
        String updatedOperatorAndVersion = def.getOperatorAndVersion();

        String packageName = def.getPackageName();

        if (overridePackageVersion && updatedOperatorAndVersion.contains(SNAPSHOT)) {
            //NB: remove this once #27 is committed; in the meantime, this allows older versions to still work as they
            //    did in Habushu 2.5.0 and earlier:
            Semver version = getPoetryVersion();

            if (version.isGreaterThanOrEqualTo("1.5.0") && !updatedOperatorAndVersion.contains("^")) {
                updatedOperatorAndVersion = replaceSnapshotWithWildcard(updatedOperatorAndVersion);
            } else {
                updatedOperatorAndVersion = replaceSnapshotWithDev(updatedOperatorAndVersion);
            }

        }

        boolean mismatch = !originalOperatorAndVersion.equals(updatedOperatorAndVersion);

        if (mismatch) {
            if (def.isActive()) {
                TomlReplacementTuple tuple = new TomlReplacementTuple(packageName, originalOperatorAndVersion, updatedOperatorAndVersion);
                replacements.put(packageName, tuple);
            } else {
                getLog().info(String.format("Package %s is not up to date with common project package definition guidance, "
                        + "but the check has been inactivated", packageName));
            }
        }
    }

    protected Semver getPoetryVersion() {
        PoetryCommandHelper poetryHelper = createPoetryCommandHelper();
        Pair<Boolean, String> poetryStatus = poetryHelper.getIsPoetryInstalledAndVersion();
        String versionAsString = poetryStatus.getRight();
        return new Semver(versionAsString);
    }

    private void logPackageMismatch(String packageName, String originalOperatorAndVersion, String updatedOperatorAndVersion) {
        getLog().warn(String.format("Package %s is not up to date with common project package definition guidance! "
                + "Currently %s, but should be %s!", packageName, originalOperatorAndVersion, updatedOperatorAndVersion));
    }

    protected void performPendingDependencyReplacements(Map<String, TomlReplacementTuple> replacements) {
        if (MapUtils.isNotEmpty(replacements)) {
            if (failOnManagedDependenciesMismatches) {
                if (updateManagedDependenciesWhenFound) {
                    getLog().warn("updateManagedDependenciesWhenFound=true will never be processed when failOnManagedDependenciesMismatches also equals true!");
                }

                throw new HabushuException("Found managed dependencies - please fix before proceeding!  "
                        + "(see 'Package abc is not up to date with common project package definition guidance!` log messages above!");
            }

            if (updateManagedDependenciesWhenFound) {
                File pyProjectTomlFile = getPoetryPyProjectTomlFile();
                String fileContent = StringUtils.EMPTY;

                try (BufferedReader reader = new BufferedReader(new FileReader(pyProjectTomlFile))) {
                    String line = reader.readLine();

                    while (line != null) {
                        if (line.contains(StringUtils.SPACE) || line.contains(EQUALS)) {
                            String key = line.substring(0, line.indexOf(StringUtils.SPACE));

                            if (key == null) {
                                key = line.substring(0, line.indexOf(EQUALS));
                            }

                            if (key != null) {
                                key = key.strip();

                                TomlReplacementTuple matchedTuple = replacements.get(key);
                                if (matchedTuple != null) {
                                    String original = TomlUtils.escapeTomlRightHandSide(matchedTuple.getOriginalOperatorAndVersion());
                                    String updated = TomlUtils.escapeTomlRightHandSide(matchedTuple.getUpdatedOperatorAndVersion());

                                    if (line.endsWith(original)) {
                                        line = line.replace(original, updated);
                                        getLog().info(String.format("Updated %s: %s --> %s", matchedTuple.getPackageName(),
                                                original, updated));
                                    }
                                }
                            }
                        }

                        fileContent += line + "\n";

                        line = reader.readLine();
                    }

                } catch (IOException e) {
                    throw new HabushuException("Problem reading pyproject.toml to update with managed dependencies!", e);
                }

                try {
                    TomlUtils.writeTomlFile(pyProjectTomlFile, fileContent);

                } catch (IOException e) {
                    throw new HabushuException("Problem writing pyproject.toml with managed dependency updates!", e);
                }

            }
        }
    }

    protected String getOperatorAndVersion(Object rawData) {
        String operatorAndVersion = null;
        if (rawData instanceof String) {
            operatorAndVersion = (String) rawData;

        } else if (rawData instanceof CommentedConfig) {
            operatorAndVersion = TomlUtils.convertCommentedConfigToToml((CommentedConfig) rawData);

        } else {
            getLog().warn(String.format("Could not process type %s - attempting to use toString() value!", rawData.getClass()));
            operatorAndVersion = rawData.toString();
        }

        return operatorAndVersion;

    }

    protected static String replaceSnapshotWithWildcard(String pomVersion) {
        return pomVersion.substring(0, pomVersion.indexOf(SNAPSHOT)) + ".*";
    }

    /**
     * This method should only be used to help shim Poetry < 1.5.0 versioning practices until Habushu updates to force
     * a minimum version of 1.5.0.
     *
     * @param pomVersion version to update
     * @return updated version
     * @deprecated shim use only, then use replaceSnapshotWithWildcard(String pomVersion) instead!
     */
    @Deprecated
    protected static String replaceSnapshotWithDev(String pomVersion) {
        return pomVersion.substring(0, pomVersion.indexOf(SNAPSHOT)) + ".dev";
    }

}
