#!/usr/bin/env python3
"""Run the hardware-independent JUnit suite with a previously populated Gradle cache.
Useful in restricted environments where Gradle's local IPC sockets are unavailable.
This does not replace a Gradle Android build or Android integration tests.
"""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--cache', type=Path, default=Path.home() / '.gradle/caches/modules-2/files-2.1')
parser.add_argument('--java', default=str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
out = root / 'build/offline-core'
out.mkdir(parents=True, exist_ok=True)
def jar(group, artifact, version):
    matches = list((args.cache / group / artifact / version).glob('*/*.jar'))
    if len(matches) != 1:
        raise RuntimeError(f'Expected one cached JAR: {group}:{artifact}:{version}; found {matches}')
    return str(matches[0])
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20')
compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.20'), stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.4.10'), jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0'),
    jar('org.jetbrains', 'annotations', '23.0.0')]
deps = [stdlib, jar('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.11.0'),
    jar('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', '1.11.0'),
    jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3')]
plugin = jar('org.jetbrains.kotlin', 'kotlin-serialization-compiler-plugin-embeddable', '2.4.20')
test_classes = ['CoreTest', 'PacketProfilesTest', 'AddressAssignmentsTest', 'PhysicalIdentityTest', 'AdvertisementHintsTest', 'GpsBreadcrumbsTest']
sources = sorted((root / 'app/src/main/java/org/blefinder/core').glob('*.kt')) + [
    root / f'app/src/test/java/org/blefinder/{name}.kt' for name in test_classes]
subprocess.run([args.java, '-cp', ':'.join(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', ':'.join(deps), '-Xplugin=' + plugin,
    '-d', str(out / 'tests.jar'), *map(str, sources)], check=True)
with zipfile.ZipFile(out / 'tests.jar', 'a') as compiled:
    resources = root / 'app/src/main/resources'
    for resource in sorted(resources.rglob('*')):
        if resource.is_file():
            compiled.write(resource, resource.relative_to(resources).as_posix())
subprocess.run([args.java, '-cp', ':'.join([str(out / 'tests.jar'), *deps]), 'org.junit.runner.JUnitCore',
    *['org.blefinder.' + name for name in test_classes]], check=True)
