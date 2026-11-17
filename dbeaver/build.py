#!/usr/bin/env python3
"""Build an installable p2 archive against a local DBeaver distribution."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def run(*command, **kwargs):
    subprocess.run([str(part) for part in command], check=True, **kwargs)


def configuration(platform, directory, additional=()):
    directory.mkdir(parents=True, exist_ok=True)
    configurator = directory / "org.eclipse.equinox.simpleconfigurator"
    configurator.mkdir(exist_ok=True)
    lines = []
    for line in (platform / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info").read_text().splitlines():
        if line.startswith("#"):
            continue
        fields = line.split(",")
        location = fields[2]
        if not location.startswith("file:"):
            fields[2] = (platform / location).resolve().as_uri()
        lines.append(",".join(fields))
    lines.extend(additional)
    (configurator / "bundles.info").write_text("\n".join(lines) + "\n")
    simple = next((platform / "plugins").glob("org.eclipse.equinox.simpleconfigurator_*.jar"))
    framework = next((platform / "plugins").glob("org.eclipse.osgi_*.jar"))
    (directory / "config.ini").write_text(
        f"osgi.bundles=reference:{simple.as_uri()}@1:start\n"
        f"osgi.framework={framework.as_uri()}\n"
        "osgi.bundles.defaultStartLevel=4\n"
        "org.eclipse.equinox.simpleconfigurator.configUrl=file:org.eclipse.equinox.simpleconfigurator/bundles.info\n"
        "osgi.configuration.cascaded=false\n"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dbeaver-home", type=Path, default=Path("/Applications/DBeaver.app/Contents/Eclipse"))
    parser.add_argument("--maven-repo", type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    platform = args.dbeaver_home.resolve()
    java_home = Path(os.environ["JAVA_HOME"])
    target = here / "target"
    target.mkdir(exist_ok=True)
    maven = ["mvn", "-B", "-f", str(here.parent / "demo/pom.xml"), "verify"]
    if args.maven_repo:
        maven.append(f"-Dmaven.repo.local={args.maven_repo.resolve()}")
    run(*maven)
    engine = here.parent / "demo/target/sql-demo.jar"
    classes = target / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(exist_ok=True)
    sources = sorted((here / "src/main/java").rglob("*.java"))
    classpath = os.pathsep.join([str(platform / "plugins/*"), str(engine)])
    run(java_home / "bin/javac", "--release", "21", "-encoding", "UTF-8",
        "-cp", classpath, "-d", classes, *sources)
    repository = target / "repository"
    if repository.exists():
        shutil.rmtree(repository)
    staging = target / "staging"
    plugins = staging / "plugins"
    features = staging / "features"
    plugins.mkdir(parents=True, exist_ok=True)
    features.mkdir(exist_ok=True)
    bundle = plugins / "io.github.vaneshik.sqlantipatterns.dbeaver_0.1.0.jar"
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(here / "META-INF/MANIFEST.MF", "META-INF/MANIFEST.MF")
        archive.write(here / "plugin.xml", "plugin.xml")
        archive.write(engine, "lib/sql-analysis.jar")
        for file in sorted(classes.rglob("*.class")):
            archive.write(file, file.relative_to(classes))
    with zipfile.ZipFile(features / "io.github.vaneshik.sqlantipatterns.feature_0.1.0.jar", "w") as archive:
        archive.write(here / "feature.xml", "feature.xml")
    config = target / "publisher-configuration"
    configuration(platform, config)
    launcher = next((platform / "plugins").glob("org.eclipse.equinox.launcher_*.jar"))
    run(java_home / "bin/java", "-jar", launcher, "-configuration", config,
        "-data", target / "publisher-workspace",
        "-application", "org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher",
        "-metadataRepository", "file:" + str(repository), "-artifactRepository", "file:" + str(repository),
        "-source", staging, "-publishArtifacts", "-compress", "-consoleLog")
    for filename in ("content.jar", "artifacts.jar"):
        if not (repository / filename).is_file():
            raise RuntimeError(f"p2 publisher did not create {filename}")
    archive = shutil.make_archive(str(target / "sql-antipatterns-0.1.0"), "zip", repository)
    print(f"Install in DBeaver using Help > Install New Software > Add > Archive\n{archive}")


if __name__ == "__main__":
    main()
