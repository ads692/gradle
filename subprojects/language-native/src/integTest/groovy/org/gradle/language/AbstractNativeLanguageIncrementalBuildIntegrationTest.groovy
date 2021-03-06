/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language

import org.gradle.internal.os.OperatingSystem
import org.gradle.nativeplatform.fixtures.AbstractInstalledToolChainIntegrationSpec
import org.gradle.nativeplatform.fixtures.AvailableToolChains
import org.gradle.nativeplatform.fixtures.RequiresInstalledToolChain
import org.gradle.nativeplatform.fixtures.ToolChainRequirement
import org.gradle.nativeplatform.fixtures.app.IncrementalHelloWorldApp
import org.gradle.test.fixtures.file.TestFile
import org.gradle.util.GUtil
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import org.junit.Assume
import spock.lang.Ignore
import spock.lang.IgnoreIf
import spock.lang.Issue

import static org.gradle.nativeplatform.fixtures.ToolChainRequirement.GccCompatible
import static org.gradle.nativeplatform.fixtures.ToolChainRequirement.VisualCpp
import static org.gradle.util.TextUtil.escapeString

abstract class AbstractNativeLanguageIncrementalBuildIntegrationTest extends AbstractInstalledToolChainIntegrationSpec {

    IncrementalHelloWorldApp app
    String mainCompileTask
    String libraryCompileTask
    TestFile sourceFile
    TestFile headerFile
    TestFile commonHeaderFile
    List<TestFile> librarySourceFiles = []

    boolean isCanBuildForMultiplePlatforms() {
        return !(toolChain instanceof AvailableToolChains.InstalledWindowsGcc)
    }

    abstract IncrementalHelloWorldApp getHelloWorldApp();

    String getCompilerTool() {
        "${app.sourceType}Compiler"
    }

    String getSourceType() {
        GUtil.toCamelCase(app.sourceType)
    }

    def "setup"() {
        app = getHelloWorldApp()
        mainCompileTask = ":compileMainExecutableMain${sourceType}"
        libraryCompileTask = ":compileHelloSharedLibraryHello${sourceType}"

        buildFile << app.pluginScript
        buildFile << app.extraConfiguration

        buildFile << """
        model {
            components {
                main(NativeExecutableSpec) {
                    binaries.all {
                        lib library: 'hello'
                    }
                }
                hello(NativeLibrarySpec) {
                    binaries.withType(SharedLibraryBinarySpec) {
                        ${app.compilerDefine("DLL_EXPORT")}
                    }
                }
            }
        }
        """
        settingsFile << "rootProject.name = 'test'"
        sourceFile = app.mainSource.writeToDir(file("src/main"))
        headerFile = app.libraryHeader.writeToDir(file("src/hello"))
        commonHeaderFile = app.commonHeader.writeToDir(file("src/hello"))
        app.librarySources.each {
            librarySourceFiles << it.writeToDir(file("src/hello"))
        }
    }

    def "does not re-execute build with no change"() {
        given:
        run "installMainExecutable"

        when:
        run "installMainExecutable"

        then:
        nonSkippedTasks.empty
    }

    @IgnoreIf({!TestPrecondition.CAN_INSTALL_EXECUTABLE.fulfilled})
    def "rebuilds executable with source file change"() {
        given:
        run "installMainExecutable"

        def install = installation("build/install/mainExecutable")

        when:
        sourceFile.text = app.alternateMainSource.content

        and:
        run "installMainExecutable"

        then:
        skipped libraryCompileTask
        skipped ":linkHelloSharedLibrary"
        skipped ":helloSharedLibrary"
        executedAndNotSkipped mainCompileTask
        executedAndNotSkipped ":linkMainExecutable"
        executedAndNotSkipped ":mainExecutable"
        executedAndNotSkipped ":installMainExecutable"

        and:
        install.assertInstalled()
        install.exec().out == app.alternateOutput
    }

    def "recompiles but does not relink executable with source comment change"() {
        given:
        run "installMainExecutable"
        maybeWait()

        when:
        sourceFile.text = sourceFile.text.replaceFirst("// Simple hello world app", "// Comment is changed")
        run "mainExecutable"

        then:
        skipped libraryCompileTask
        skipped ":linkHelloSharedLibrary"
        skipped ":helloSharedLibrary"

        executedAndNotSkipped mainCompileTask

        // Visual C++ compiler embeds a timestamp in every object file, so relinking is always required after recompiling
        if (AbstractInstalledToolChainIntegrationSpec.toolChain.visualCpp) {
            executedAndNotSkipped ":linkMainExecutable"
            executedAndNotSkipped ":mainExecutable"
        } else if(objectiveCWithAslr()){
            executed ":linkHelloSharedLibrary", ":helloSharedLibrary"
            executed ":linkMainExecutable", ":mainExecutable"
        } else {
            skipped ":linkMainExecutable"
            skipped ":mainExecutable"
        }
    }
    @Requires(TestPrecondition.CAN_INSTALL_EXECUTABLE)
    def "recompiles library and relinks executable with library source file change"() {
        given:
        run "installMainExecutable"
        maybeWait()
        def install = installation("build/install/mainExecutable")

        when:
        for (int i = 0; i < librarySourceFiles.size(); i++) {
            TestFile sourceFile = librarySourceFiles.get(i);
            sourceFile.text = app.alternateLibrarySources[i].content
        }

        and:
        run "installMainExecutable"

        then:
        executedAndNotSkipped libraryCompileTask
        executedAndNotSkipped ":linkHelloSharedLibrary"
        executedAndNotSkipped ":helloSharedLibrary"
        skipped mainCompileTask
        executedAndNotSkipped ":linkMainExecutable"
        executedAndNotSkipped ":mainExecutable"
        executedAndNotSkipped ":installMainExecutable"

        and:
        install.assertInstalled()
        install.exec().out == app.alternateLibraryOutput
    }

    def "recompiles binary when header file changes"() {
        given:
        run "installMainExecutable"
        maybeWait()

        when:
        headerFile << """
            int unused();
"""

        run "mainExecutable"

        then:
        executedAndNotSkipped libraryCompileTask
        executedAndNotSkipped mainCompileTask

        // Visual C++ compiler embeds a timestamp in every object file, so relinking is always required after recompiling
        if (AbstractInstalledToolChainIntegrationSpec.toolChain.visualCpp) {
            executedAndNotSkipped ":linkHelloSharedLibrary", ":helloSharedLibrary"
            executedAndNotSkipped ":linkMainExecutable", ":mainExecutable"
        } else if(objectiveCWithAslr()){
            executed ":linkHelloSharedLibrary", ":helloSharedLibrary"
            executed ":linkMainExecutable", ":mainExecutable"
        } else {
            skipped ":linkHelloSharedLibrary", ":helloSharedLibrary"
            skipped ":linkMainExecutable", ":mainExecutable"
        }
    }

    def "recompiles binary when header file changes in a way that does not affect the object files"() {
        given:
        run "installMainExecutable"
        maybeWait()

        when:
        headerFile << """
// Comment added to the end of the header file
"""
        run "mainExecutable"

        then:
        executedAndNotSkipped libraryCompileTask
        executedAndNotSkipped mainCompileTask

        // Visual C++ compiler embeds a timestamp in every object file, so relinking is always required after recompiling
        if (AbstractInstalledToolChainIntegrationSpec.toolChain.visualCpp) {
            executedAndNotSkipped ":linkHelloSharedLibrary", ":helloSharedLibrary"
            executedAndNotSkipped ":linkMainExecutable", ":mainExecutable"
        } else if(objectiveCWithAslr()){
            executed ":linkHelloSharedLibrary", ":helloSharedLibrary"
            executed ":linkMainExecutable", ":mainExecutable"
        } else {
            skipped ":linkHelloSharedLibrary", ":helloSharedLibrary"
            skipped ":linkMainExecutable", ":mainExecutable"
        }
    }

    // compiling Objective-C and Objective-Cpp with clang generates
    // random different object files (related to ASLR settings)
    // We saw this behaviour only on linux so far.
    boolean objectiveCWithAslr() {
        return (sourceType == "Objc" || sourceType == "Objcpp") &&
                OperatingSystem.current().isLinux() &&
                AbstractInstalledToolChainIntegrationSpec.toolChain.displayName == "clang"
    }

    @Requires(TestPrecondition.CAN_INSTALL_EXECUTABLE)
    def "rebuilds binary with compiler option change"() {
        given:
        run "installMainExecutable"

        def install = installation("build/install/mainExecutable")

        when:
        buildFile << """
        model {
            components {
                hello {
                    binaries.all {
                        ${helloWorldApp.compilerArgs("-DFRENCH")}
                    }
                }
            }
        }
"""

        maybeWait()
        run "installMainExecutable"

        then:
        executedAndNotSkipped libraryCompileTask
        executedAndNotSkipped ":linkHelloSharedLibrary"
        executedAndNotSkipped ":helloSharedLibrary"
        skipped mainCompileTask
        executedAndNotSkipped ":linkMainExecutable"
        executedAndNotSkipped ":mainExecutable"
        executedAndNotSkipped ":installMainExecutable"

        and:
        install.assertInstalled()
        install.exec().out == app.frenchOutput
    }

    @Requires(TestPrecondition.CAN_INSTALL_EXECUTABLE)
    def "rebuilds binary with target platform change"() {
        Assume.assumeTrue(canBuildForMultiplePlatforms)
        given:
        buildFile << """
    model {
        platforms {
            platform_x86 {
                architecture 'x86'
            }
            platform_x64 {
                architecture 'x86-64'
            }
        }
        components {
            main.targetPlatform "platform_x86"
            hello.targetPlatform "platform_x86"
        }
    }
"""
        run "mainExecutable"

        when:
        buildFile.text = buildFile.text.replace("platform_x86", "platform_x64")
        run "mainExecutable"

        then:
        executedAndNotSkipped libraryCompileTask, mainCompileTask
        executedAndNotSkipped ":linkHelloSharedLibrary"
        executedAndNotSkipped ":helloSharedLibrary", ":mainExecutable"
    }

    def "relinks binary when set of input libraries changes"() {
        given:
        run "mainExecutable", "helloStaticLibrary"

        def executable = executable("build/binaries/mainExecutable/main")
        def snapshot = executable.snapshot()

        when:
        buildFile.text = buildFile.text.replace("lib library: 'hello'", "lib library: 'hello', linkage: 'static'")
        run "mainExecutable"

        then:
        skipped ":helloStaticLibrary"
        skipped mainCompileTask
        executedAndNotSkipped ":linkMainExecutable"
        executedAndNotSkipped ":mainExecutable"

        and:
        executable.assertHasChangedSince(snapshot)
    }

    def "relinks binary but does not recompile when linker option changed"() {
        given:
        run "mainExecutable"

        when:
        def executable = executable("build/binaries/mainExecutable/main")
        def snapshot = executable.snapshot()

        and:
        def linkerArgs = toolChain.isVisualCpp() ? "'/DEBUG'" : OperatingSystem.current().isMacOsX() ? "'-Xlinker', '-no_pie'" : "'-Xlinker', '-q'";
        linkerArgs = escapeString(linkerArgs)
        buildFile << """
        model {
            components {
                main {
                    binaries.all {
                        linker.args ${escapeString(linkerArgs)}
                    }
                }
            }
        }
"""

        run "mainExecutable"

        then:
        skipped libraryCompileTask
        skipped ":linkHelloSharedLibrary"
        skipped ":helloSharedLibrary"
        skipped mainCompileTask
        executedAndNotSkipped ":linkMainExecutable"
        executedAndNotSkipped ":mainExecutable"

        and:
        executable.assertExists()

        if (toolChain.id != "mingw") { // Identical binary is produced on mingw
            executable.assertHasChangedSince(snapshot)
        }
    }

    def "cleans up stale object files when executable source file renamed"() {
        given:
        run "installMainExecutable"

        def oldObjFile = objectFileFor(sourceFile)
        def newObjFile = objectFileFor(sourceFile.getParentFile().file("changed_${sourceFile.name}"))
        assert oldObjFile.file
        assert !newObjFile.file

        final source = sourceFile

        when:
        rename(source)
        run "mainExecutable"

        then:
        skipped libraryCompileTask
        skipped ":linkHelloSharedLibrary"
        skipped ":helloSharedLibrary"
        executedAndNotSkipped mainCompileTask
        executedAndNotSkipped ":linkMainExecutable"
        executedAndNotSkipped ":mainExecutable"

        and:
        !oldObjFile.file
        newObjFile.file
    }

    def "cleans up stale object files when library source file renamed"() {
        when:
        run "helloStaticLibrary"

        then:
        String objectFilesPath = "build/objs/helloStaticLibrary/hello${sourceType}"
        def oldObjFile = objectFileFor(librarySourceFiles[0], objectFilesPath)
        def newObjFile = objectFileFor( librarySourceFiles[0].getParentFile().file("changed_${librarySourceFiles[0].name}"), objectFilesPath)
        assert oldObjFile.file
        assert !newObjFile.file

        assert staticLibrary("build/binaries/helloStaticLibrary/hello").listObjectFiles().contains(oldObjFile.name)

        when:
        librarySourceFiles.each { rename(it) }
        run "helloStaticLibrary"

        then:
        executedAndNotSkipped libraryCompileTask.replace("Shared", "Static")
        executedAndNotSkipped ":createHelloStaticLibrary"
        executedAndNotSkipped ":helloStaticLibrary"

        and:
        !oldObjFile.file
        newObjFile.file

        and:
        assert staticLibrary("build/binaries/helloStaticLibrary/hello").listObjectFiles().contains(newObjFile.name)
        assert !staticLibrary("build/binaries/helloStaticLibrary/hello").listObjectFiles().contains(oldObjFile.name)
    }

    @RequiresInstalledToolChain(GccCompatible)
    def "recompiles binary when imported header file changes"() {
        sourceFile.text = sourceFile.text.replaceFirst('#include "hello.h"', "#import \"hello.h\"")
        if(buildingCorCppWithGcc()) {
            buildFile << """
                model {
                    //support for #import on c/cpp is deprecated in gcc
                    binaries {
                        all { ${compilerTool}.args '-Wno-deprecated'; }
                    }
                }
            """
        }

        given:
        run "installMainExecutable"


        when:
        headerFile << """
            int unused();
"""
        run "mainExecutable"

        then:
        executedAndNotSkipped libraryCompileTask
        executedAndNotSkipped mainCompileTask

        if(objectiveCWithAslr()){
            executed ":linkHelloSharedLibrary", ":helloSharedLibrary"
            executed ":linkMainExecutable", ":mainExecutable"
        } else {
            skipped ":linkHelloSharedLibrary", ":helloSharedLibrary"
            skipped ":linkMainExecutable", ":mainExecutable"
        }
    }

    @RequiresInstalledToolChain(VisualCpp)
    def "cleans up stale debug files when changing from debug to non-debug"() {

        given:
        buildFile << """
            model {
                binaries {
                    all { ${compilerTool}.args ${toolChain.meets(ToolChainRequirement.VisualCpp2013) ? "'/Zi', '/FS'" : "'/Zi'"}; linker.args '/DEBUG'; }
                }
            }
        """
        run "mainExecutable"

        def executable = executable("build/binaries/mainExecutable/main")
        executable.assertDebugFileExists()

        when:
        buildFile << """
            model {
                binaries {
                    all { ${compilerTool}.args.clear(); linker.args.clear(); }
                }
            }
        """
        run "mainExecutable"

        then:
        executedAndNotSkipped libraryCompileTask
        executedAndNotSkipped ":helloSharedLibrary"
        executedAndNotSkipped mainCompileTask
        executedAndNotSkipped ":linkMainExecutable"
        executedAndNotSkipped ":mainExecutable"

        and:
        executable.assertDebugFileDoesNotExist()
    }

    @Issue("GRADLE-3248")
    def "incremental compilation isn't considered up-to-date when compilation fails"() {
        expect:
        succeeds mainCompileTask

        when:
        app.brokenFile.writeToDir(file("src/main"))

        then:
        fails mainCompileTask

        when:
        // nothing changes

        expect:
        // build should still fail
        fails mainCompileTask
    }

    @Ignore("Test demonstrates missing functionality in incremental build with C++")
    def "recompiles binary when header file with relative path changes"() {
        when:
        buildFile << """
plugins {
    id 'cpp'
}
model {
    components {
        main(NativeExecutableSpec)
    }
}
"""

        file("src/main/cpp/main.cpp") << """
            #include "../not_included/hello.h"

            int main () {
              sayHello();
              return 0;
            }
"""

        def headerFile = file("src/main/not_included/hello.h") << """
            void sayHello();
"""

        file("src/main/cpp/hello.cpp") << """
            #include <iostream>

            void sayHello() {
                std::cout << "HELLO" << std::endl;
            }
"""
        then:
        succeeds "mainExecutable"
        executable("build/binaries/mainExecutable/main").exec().out == "HELLO\n"

        when:
        headerFile.text = """
            NOT A VALID HEADER FILE
"""
        then:
        fails "mainExecutable"
        and:
        executedAndNotSkipped "compileMainExecutableMainCpp"
    }


    def buildingCorCppWithGcc() {
        return toolChain.meets(ToolChainRequirement.Gcc) && (sourceType == "C" || sourceType == "Cpp")
    }

    private void maybeWait() {
        if (toolChain.visualCpp) {
            def now = System.currentTimeMillis()
            def nextSecond = now % 1000
            Thread.sleep(1200 - nextSecond)
        }
    }

    static boolean rename(TestFile sourceFile) {
        final newFile = new File(sourceFile.getParentFile(), "changed_${sourceFile.name}")
        newFile << sourceFile.text
        sourceFile.delete()
    }
}
