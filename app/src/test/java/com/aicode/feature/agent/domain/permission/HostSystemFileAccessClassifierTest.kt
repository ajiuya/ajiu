package com.aicode.feature.agent.domain.permission

import com.aicode.feature.agent.domain.permission.HostSystemFileAccessClassifier.Namespace
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSystemFileAccessClassifierTest {
    private fun targets(command: String, namespace: Namespace = Namespace.HOST) =
        HostSystemFileAccessClassifier.commandTargets(command, namespace)

    private fun assess(command: String, namespace: Namespace = Namespace.HOST) =
        HostSystemFileAccessClassifier.assessCommand(command, namespace)

    private fun asks(assessment: HostSystemFileAccessClassifier.Assessment) =
        assessment.uncertainties.isNotEmpty() || assessment.targets.any { it.startsWith("WRITE:") || it.startsWith("READ_WRITE:") }

    @Test fun reviewRegression_fdDuplicationAndCloseAreNotFilesButNumericFilesRemainTargets() {
        for ((namespace, directory) in listOf(Namespace.HOST to "/system", Namespace.CHROOT_GUEST to "/sys")) {
            for (command in listOf("id 2>&1", "id 0<&1", "id 3>&2-", "id 3<&2-", "id 2>&-", "id 0<&-")) {
                assertTrue("$namespace: $command", targets("cd $directory; $command", namespace).isEmpty())
            }
            assertEquals(setOf("WRITE:$directory/1"), targets("cd $directory; printf x >1", namespace))
            assertEquals(setOf("READ:$directory/0"), targets("cd $directory; cat <0", namespace))
            assertEquals(setOf("WRITE:$directory/probe"), targets("cd $directory; printf x >probe 2>&1", namespace))
            assertEquals(setOf("READ:$directory/probe"), targets("cat < $directory/probe", namespace))
            assertEquals(setOf("WRITE:$directory/probe"), targets("printf x >&$directory/probe", namespace))
        }
    }

    @Test fun reviewRegression_hereStringsAndLiteralHereDocumentBodiesAreDataNotCommands() {
        for ((namespace, path) in listOf(Namespace.HOST to "/system/build.prop", Namespace.CHROOT_GUEST to "/sys/probe")) {
            for (command in listOf(
                "cat <<< '$path'",
                "cat <<< \"$path\"",
                "cat <<< '\$(cat $path)'",
                "cat <<'EOF'\ncat $path\nEOF",
                "cat <<\"EOF\"\ncat $path\nEOF",
                "cat <<EOF\ncat $path\nEOF",
                "cat <<-'EOF'\n\tcat $path\n\tEOF",
                "cat <<'EOF'\n\$(cat $path)\nEOF",
                "cat <<'EOF'\n`cat $path`\nEOF"
            )) {
                assertTrue("$namespace: $command", targets(command, namespace).isEmpty())
            }
        }
    }

    @Test fun reviewRegression_unquotedHereDataRetainsRealInlineExpansionsAndFollowingCommands() {
        for ((namespace, path) in listOf(Namespace.HOST to "/system/build.prop", Namespace.CHROOT_GUEST to "/sys/probe")) {
            for (command in listOf(
                "cat <<< \"\$(cat $path)\"",
                "cat <<EOF\n\$(cat $path)\nEOF",
                "cat <<EOF\n`cat $path`\nEOF",
                "cat <<'EOF'\nordinary literal data\nEOF\ncat $path"
            )) {
                assertEquals("$namespace: $command", setOf("READ:$path"), targets(command, namespace))
            }
        }
    }

    @Test fun hostFilesAreRecognizedOnlyInKnownFileOperations() {
        for (command in listOf(
            "cat /system/build.prop", "cat -n '/system/build.prop'", "/system/bin/cat /system/build.prop",
            "command cat /system/build.prop", "env NAME=value toybox cat /system/build.prop",
            "sh -c 'cat /system/build.prop'", "su -c 'cat /system/build.prop'",
            "echo \"\$(cat /system/build.prop)\"", "echo `cat /system/build.prop`"
        )) assertTrue(command, "READ:/system/build.prop" in targets(command))
    }

    @Test fun literalArgumentsAndExecutableLocationsAreNotFileOperands() {
        for (command in listOf(
            "echo '/system/x'", "printf '%s' '/sys/x'", "/system/bin/id", "/system/bin/sh -c 'id'",
            "command -v /system/bin/id", "grep '/system/x' README.md", "sed 's|/sys/x|x|' README.md",
            "cat /systematic/file", "cat /syslog/file", "unknown-program /system/x",
            "cd /system; chmod 644 /data/local/tmp/probe"
        )) assertTrue(command, targets(command).isEmpty())
    }

    // Compatibility targets omit opaque evidence; only the full Assessment determines approval.
    @Test fun ordinaryStorageAndOpaqueDynamicPathsCanHaveNoLiteralTargets() {
        for (command in listOf(
            "cat /data/local/tmp/probe", "touch /sdcard/Download/probe", "rm -rf /storage/emulated/0/test",
            "sh /data/local/tmp/unknown.sh", "sh -c '\$SCRIPT'", "rm -rf \"\$TARGET\"",
            "python3 -c 'opaque_script()'", "id >\$TARGET", "cat /system/../sdcard/file"
        )) assertTrue(command, targets(command).isEmpty())
    }

    @Test fun inputOutputAndReadWriteRedirectsRetainDirectionAndLiteralTarget() {
        assertEquals(setOf("READ:/system/build.prop"), targets("cat < /system/build.prop"))
        assertEquals(setOf("WRITE:/sys/kernel/probe"), targets("printf 1 2>>'/sys/kernel/probe'"))
        assertEquals(setOf("READ_WRITE:/system/probe"), targets("exec 3<>/system/probe"))
        assertEquals(setOf("WRITE:/system/probe"), targets("sh -c 'id >/system/probe'"))
        assertTrue(targets("id >/dev/null 2>&1").isEmpty())
        assertTrue(targets("id >/dev/'null'").isEmpty())
        assertTrue(targets("printf '%s' '> /system/probe'").isEmpty())
    }

    @Test fun directoryChangesAndWrappersExposeKnownRelativeHostTargets() {
        assertEquals(setOf("READ:/sys/kernel/probe"), targets("cd /sys; cat kernel/probe"))
        assertEquals(setOf("WRITE:/system/probe"), targets("cd /system && command touch ./probe"))
        assertEquals(setOf("READ:/sys/kernel/probe"), targets("sh -c 'cd /sys; cat kernel/probe'"))
        assertTrue(targets("cd /data/local/tmp; touch probe").isEmpty())
    }

    @Test fun copyDdSearchAndFileOptionsRetainFileEvidenceNotPatterns() {
        assertEquals(setOf("WRITE:/system/probe"), targets("cp /tmp/input /system/probe"))
        assertTrue("WRITE:/dev/block/by-name/boot" in targets("dd if=/tmp/input of=/dev/block/by-name/boot"))
        assertTrue("READ:/system/patterns" in targets("grep -f /system/patterns /tmp/input"))
        assertTrue("READ:/sys/kernel/probe" in targets("rg -e probe /sys/kernel/probe"))
        assertTrue("READ:/sys/kernel/probe" in targets("rg --files /sys/kernel/probe"))
        assertTrue("READ:/sys/kernel/probe" in targets("rg -eprobe /sys/kernel/probe"))
        assertTrue("READ:/sys/kernel/probe" in targets("head -n 1 /sys/kernel/probe"))
        assertTrue("WRITE:/system/probe" in targets("sed -i 's/a/b/' /system/probe"))
        assertTrue("WRITE:/system/probe" in targets("touch -t 202601010000 /system/probe"))
    }

    @Test fun hostWholeFilesystemAndSystemAncestorsDifferFromOrdinaryDataSubtrees() {
        for (command in listOf("rm -rf /", "rm -rf /*", "rm -rf /data", "rm -rf /data/system")) {
            assertFalse(command, targets(command).isEmpty())
        }
        assertTrue(targets("rm -rf /data/local/tmp/build").isEmpty())
    }

    @Test fun guestEtcUsrSystemAndPackageManagerHaveNoLiteralAndroidHostTargets() {
        for (command in listOf(
            "cat /etc/passwd", "touch /usr/local/bin/probe", "rm -rf /etc", "apt update",
            "apt-get install -y make", "sudo id", "cat /system/guest-file", "sh /root/unknown.sh"
        )) assertTrue(command, targets(command, Namespace.CHROOT_GUEST).isEmpty())
        for (command in listOf("cat /proc/sys/kernel/hostname", "touch /sys/kernel/probe", "dd of=/dev/block/boot")) {
            assertFalse(command, targets(command, Namespace.CHROOT_GUEST).isEmpty())
        }
    }

    @Test fun typedPathsAreClassifiedWithoutScanningFileContentsOrEditValues() {
        assertEquals(setOf("READ:/proc/sys/kernel/hostname"), HostSystemFileAccessClassifier.fileToolTargets(
            "readFile", mapOf("path" to JsonPrimitive("/proc/sys/kernel/hostname")), Namespace.CHROOT_GUEST
        ))
        for (tool in listOf("writeFile", "editFile")) {
            assertEquals(setOf("WRITE:/sys/probe"), HostSystemFileAccessClassifier.fileToolTargets(
                tool, mapOf("path" to JsonPrimitive("/sys/probe")), Namespace.CHROOT_GUEST
            ))
            assertTrue(HostSystemFileAccessClassifier.fileToolTargets(tool, mapOf(
                "path" to JsonPrimitive("/etc/guest-file"), "content" to JsonPrimitive("/proc/sys/probe"),
                "old_string" to JsonPrimitive("/sys/probe"), "new_string" to JsonPrimitive("/dev/block/boot")
            ), Namespace.CHROOT_GUEST).isEmpty())
        }
    }

    @Test fun typedArraysAndExplorerArgumentsCoverHostInterfacesWithoutPatternFalsePositives() {
        for ((tool, key) in listOf("sendFile" to "paths", "viewImage" to "images")) {
            assertEquals(setOf("READ:/sys/probe"), HostSystemFileAccessClassifier.fileToolTargets(tool, mapOf(
                key to JsonArray(listOf(JsonPrimitive("/etc/guest"), JsonPrimitive("/sys/probe")))
            ), Namespace.CHROOT_GUEST))
        }
        assertEquals(setOf("READ:/sys/probe"), HostSystemFileAccessClassifier.fileToolTargets(
            "list", mapOf("args" to JsonPrimitive("-la /sys/probe")), Namespace.CHROOT_GUEST
        ))
        assertEquals(setOf("READ:/proc/sys"), HostSystemFileAccessClassifier.fileToolTargets(
            "search", mapOf("args" to JsonPrimitive("-n -e pattern /proc/sys")), Namespace.CHROOT_GUEST
        ))
        assertTrue(HostSystemFileAccessClassifier.fileToolTargets("search", mapOf(
            "args" to JsonPrimitive("-n '/sys/probe' /etc")
        ), Namespace.CHROOT_GUEST).isEmpty())
    }

    @Test fun hostWritePolicyRegression_readonlyProofDoesNotAskAndPrintedPathsAreNotAccesses() {
        for (command in listOf(
            "cat /system/build.prop", "head -n 1 /proc/version", "cat \"\$X\"", "cat <\"\$X\"",
            "grep '/system/x' /tmp/input", "rg --files /sys/kernel", "sed 's|/system/x|ordinary|' /tmp/input",
            "find /system -name '/sys/pattern' -print", "id", "echo /system/x", "printf '%s' /sys/x",
            "true", "false", "pwd", "uname -a", "date +%F", "ps -A", "command -v /system/bin/id"
        )) assertFalse(command, asks(assess(command)))
        assertEquals(setOf("READ:/system/build.prop"), assess("cat /system/build.prop").targets)
        assertTrue(assess("echo /system/x").targets.isEmpty())
        assertFalse(asks(assess("cat /proc/sys/kernel/hostname", Namespace.CHROOT_GUEST)))
    }

    @Test fun hostWritePolicyRegression_readCopyWriteMoveAndLinkDirections() {
        for (command in listOf(
            "cp /system/source /tmp/destination", "cp -t /tmp /system/source",
            "ln -s /system/source /tmp/link",
            "touch -r /system/source /tmp/destination", "chmod --reference=/system/source /tmp/destination",
            "chown --reference /system/source /tmp/destination", "dd if=/system/source of=/tmp/destination"
        )) assertFalse(command, asks(assess(command)))
        assertEquals(setOf("READ:/system/source"), targets("cp /system/source /tmp/destination"))
        assertEquals(setOf("WRITE:/system/destination"), targets("cp /tmp/source /system/destination"))
        assertEquals(setOf("WRITE:/system/source", "WRITE:/system/destination"), targets("mv /system/source /system/destination"))
        assertEquals(setOf("READ_WRITE:/system/source", "WRITE:/system/destination"), targets("ln /system/source /system/destination"))
        assertEquals(setOf("WRITE:/system/destination"), targets("ln -s /system/source /system/destination"))
        assertEquals(setOf("READ:/system/source", "WRITE:/system/destination"), targets("touch -r /system/source /system/destination"))
        assertEquals(setOf("READ:/system/source", "WRITE:/system/destination"), targets("chmod --reference /system/source /system/destination"))
        assertEquals(setOf("READ:/system/source", "WRITE:/system/destination"), targets("dd if=/system/source of=/system/destination"))
        assertTrue(asks(assess("mv /system/source /tmp/destination")))
        assertTrue(asks(assess("cp -t /system /tmp/source")))
    }

    @Test fun hostWritePolicyRegression_writeProofAndGuestPathOperationsRemainDistinct() {
        for (namespace in Namespace.values()) {
            for (command in listOf("printf 1 >/sys/kernel/probe", "touch /proc/sys/probe", "dd of=/dev/block/boot")) {
                assertTrue("$namespace: $command", asks(assess(command, namespace)))
            }
            for (command in listOf("touch /tmp/file", "rm /data/local/tmp/file", "printf x >/dev/null")) {
                assertFalse("$namespace: $command", asks(assess(command, namespace)))
            }
        }
        for (command in listOf("touch /etc/guest", "rm -rf /usr/local/test", "mkdir /var/lib/test", "cp /etc/source /usr/local/dest")) {
            assertFalse(command, asks(assess(command, Namespace.CHROOT_GUEST)))
        }
        for (command in listOf("touch /system/file", "rm -rf /data/system", "sed -i 's/a/b/' /system/file", "find /system -delete", "exec 3<>/system/file")) {
            assertTrue(command, asks(assess(command)))
        }
        assertEquals(setOf("WRITE:/system"), targets("find /system -delete"))
        assertTrue(asks(assess("rm -rf /", Namespace.CHROOT_GUEST)))
    }

    @Test fun hostWritePolicyRegression_dynamicWritersAndUnknownDirectoryAskButReadersDoNot() {
        for (command in listOf(
            "touch \"\$TARGET\"", "rm -rf \"\$TARGET\"", "cp /tmp/input \"\$TARGET\"",
            "mv \"\$SOURCE\" /tmp/output", "ln -s literal \"\$TARGET\"", "dd of=\"\$TARGET\"",
            "printf x >\"\$TARGET\"", "touch relative", "cd \"\$DIRECTORY\" && touch relative",
            "sed -i 's/a/b/' \"\$TARGET\"", "find \"\$DIRECTORY\" -delete"
        )) {
            val assessment = assess(command)
            assertTrue(command, assessment.uncertainties.isNotEmpty())
            assertTrue(command, asks(assessment))
        }
        assertFalse(asks(assess("cat \"\$TARGET\"")))
        assertFalse(asks(assess("ln -s \"\$SOURCE\" /tmp/output"))) // Source is link text, not a read/write path.
    }

    @Test fun hostWritePolicyRegression_opaqueUnknownProgramsScriptsAndDynamicShellBodiesAsk() {
        for (command in listOf(
            "unknown-program /system/x", "/tmp/cat /system/x", "/tmp/sh -c 'id'",
            "sh /tmp/unknown.sh", "sh -c \"\$SCRIPT\"", "sh -c '\$SCRIPT'", "sh",
            "python3 -c 'print(1)'", "node /tmp/script.js", "perl -e 'print 1'",
            "BASH_ENV=/tmp/unknown bash -c 'id'", "env LD_PRELOAD=/tmp/unknown cat /system/x",
            "echo \"\$(unknown-program)\"", "cat <<EOF\n\$(unknown-program)\nEOF"
        )) assertTrue(command, asks(assess(command)))
        for (command in listOf("apt update", "apt-get install -y make", "npm install")) {
            assertTrue(command, asks(assess(command, Namespace.CHROOT_GUEST)))
        }
        assertTrue(assess("unknown-program /system/x").targets.isEmpty())
        assertTrue(assess("/tmp/cat /system/x").targets.isEmpty())
        assertEquals(setOf("unknown-executable"), assess("unknown-program secret-token").uncertainties)
        assertFalse(asks(assess("sh -c 'cat /system/build.prop'")))
        assertTrue(asks(assess("sh -c 'touch /system/probe'")))
    }

    @Test fun hostWritePolicyRegression_cdAndMixedReadWriteCommandsRetainAllEvidence() {
        assertEquals(setOf("WRITE:/system/probe"), targets("cd /system && touch ./probe"))
        assertFalse(asks(assess("cd /data/local/tmp && touch probe")))
        assertTrue(asks(assess("cd /system | cat; touch probe"))) // Pipeline cd cannot establish parent cwd.
        val mixed = assess("cat /system/read; cp /tmp/input /system/write; unknown-program")
        assertEquals(setOf("READ:/system/read", "WRITE:/system/write"), mixed.targets)
        assertEquals(setOf("unknown-executable"), mixed.uncertainties)
        assertTrue(asks(mixed))
        assertFalse(asks(assess("cat /system/read; touch /tmp/write")))
    }

    @Test fun hostWritePolicyRegression_knownCodeExecutionNeverMasqueradesAsReadonly() {
        for (command in listOf(
            "sed 'w /system/output' /tmp/input", "sed 'e touch /system/output' /tmp/input",
            "sed 's/a/b/w /system/output' /tmp/input", "sed 's/a/b/e' /tmp/input", "sed -f /tmp/program /tmp/input",
            "awk '{print}' /system/input", "find /tmp -exec touch /system/output \\;",
            "find /tmp -execdir sh -c 'touch /system/output' \\;", "find /tmp -ok id \\;",
            "rg --pre unknown-program pattern /system/input", "rg --pre-glob '*.txt' pattern /tmp/input",
            "tar -tf /tmp/archive", "curl https://example.invalid", "wget -O /system/output example.invalid",
            "install --strip-program=unknown-program -s /tmp/input /tmp/output"
        )) assertTrue(command, asks(assess(command)))
        assertTrue("sed-program" in assess("sed -f /tmp/program /tmp/input").uncertainties)
        assertTrue("find-execution" in assess("find /tmp -exec touch /system/output \\;").uncertainties)
        assertFalse(asks(assess("sed -n 's/a/b/gp' /system/input")))
    }

    @Test fun hostWritePolicyRegression_hereDataAndDescriptorsStayDataUnlessFedToCode() {
        for (command in listOf(
            "cat <<'EOF'\nunknown-program /system/x\nEOF", "cat <<< '\$(unknown-program)'",
            "id 2>&1", "id 3>&2-", "id 0<&-"
        )) assertFalse(command, asks(assess(command)))
        for (command in listOf(
            "sh <<'EOF'\ntouch /system/x\nEOF", "python3 <<'EOF'\nprint(1)\nEOF",
            "cat <<EOF\n\$(unknown-program)\nEOF"
        )) assertTrue(command, asks(assess(command)))
        assertEquals(setOf("WRITE:/system/1"), targets("cd /system; printf x >1"))
    }

    @Test fun hostWritePolicyRegression_typedPathsDoNotScanContentOrExpandShellText() {
        for (tool in listOf("writeFile", "editFile")) {
            val ordinaryPath = HostSystemFileAccessClassifier.assessFileTool(tool, mapOf(
                "path" to JsonPrimitive("/tmp/\$LITERAL"), "content" to JsonPrimitive("touch /system/secret"),
                "old_string" to JsonPrimitive("/sys/x"), "new_string" to JsonPrimitive("/dev/block/boot")
            ), Namespace.HOST)
            assertFalse(tool, asks(ordinaryPath))
            assertTrue(ordinaryPath.targets.isEmpty())
            val host = HostSystemFileAccessClassifier.assessFileTool(tool, mapOf(
                "path" to JsonPrimitive("/system/\$LITERAL")
            ), Namespace.HOST)
            assertEquals(setOf("WRITE:/system/\$LITERAL"), host.targets)
            assertTrue(host.uncertainties.isEmpty())
        }
        assertFalse(asks(HostSystemFileAccessClassifier.assessFileTool("writeFile", mapOf(
            "path" to JsonPrimitive("~/workspace/output"), "content" to JsonPrimitive("unknown-program")
        ), Namespace.HOST)))
        assertTrue(asks(HostSystemFileAccessClassifier.assessFileTool("writeFile", mapOf(
            "path" to JsonPrimitive("relative-unknown-directory")
        ), Namespace.HOST)))
        assertFalse(asks(HostSystemFileAccessClassifier.assessFileTool("search", mapOf(
            "args" to JsonPrimitive("'/system/pattern' /tmp/input")
        ), Namespace.HOST)))
    }

    @Test fun hostWritePolicyRegression_assessmentsAreImmutableValueSnapshots() {
        val captured = assess("cat /system/read; unknown-program credential-not-a-reason")
        assertEquals(captured, assess("cat /system/read; unknown-program credential-not-a-reason"))
        assertEquals(captured, captured.copy())
        assertThrows(UnsupportedOperationException::class.java) { (captured.targets as MutableSet<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (captured.uncertainties as MutableSet<String>).add("forged") }
        assertEquals(setOf("READ:/system/read"), captured.targets)
        assertEquals(setOf("unknown-executable"), captured.uncertainties)
        val args = mutableMapOf("path" to JsonPrimitive("/sys/original"))
        val typed = HostSystemFileAccessClassifier.assessFileTool("writeFile", args, Namespace.HOST)
        args["path"] = JsonPrimitive("/tmp/later")
        assertEquals(setOf("WRITE:/sys/original"), typed.targets)
        assertFalse(asks(assess("id")))
        assertThrows(UnsupportedOperationException::class.java) { (assess("id").uncertainties as MutableSet<String>).add("later") }
        assertEquals(setOf("unknown-executable"), captured.uncertainties)
    }

    @Test fun hostWriteReviewRegression_childInfoFlagCannotSuppressCommandExecution() {
        val result = assess("command sh -c 'touch /system/probe' -v")
        assertTrue(asks(result))
        assertTrue("opaque-wrapper" in result.uncertainties)
        assertFalse(asks(assess("command -v sh"))) // Actual command metadata mode does not run sh.
    }

    @Test fun hostWriteReviewRegression_scriptArgumentsAreNotShellStartupOptions() {
        for (command in listOf("sh /tmp/unknown.sh -c id", "sh /tmp/unknown.sh --command id")) {
            val result = assess(command)
            assertTrue(command, asks(result))
            assertTrue(command, "opaque-shell-input" in result.uncertainties)
        }
        assertFalse(asks(assess("sh -c 'cat /system/probe'")))
        assertTrue(asks(assess("sh -c 'touch /system/probe'")))
    }

    @Test fun hostWriteReviewRegression_hardLinksWriteSourceInodeButCopiesAndSymbolicLinksDoNot() {
        for (command in listOf(
            "ln /data/adb/source /data/local/tmp/link",
            "cp -l /data/adb/source /data/local/tmp/link",
            "cp --link /data/adb/source /data/local/tmp/link"
        )) {
            val result = assess(command)
            assertEquals(command, setOf("READ_WRITE:/data/adb/source"), result.targets)
            assertTrue(command, asks(result))
        }
        for (command in listOf("cat /system/probe", "cp /system/probe /tmp/copy")) {
            val result = assess(command)
            assertEquals(command, setOf("READ:/system/probe"), result.targets)
            assertFalse(command, asks(result))
        }
        val symbolic = assess("ln -s /system/probe /tmp/link")
        assertTrue(symbolic.targets.isEmpty())
        assertFalse(asks(symbolic))
    }

    @Test fun hostWriteReviewRegression_envOptionOperandDoesNotHideStartupAssignment() {
        val result = assess("env -u UNUSED BASH_ENV=/tmp/unknown bash -c id")
        assertTrue(asks(result))
        assertTrue("startup-environment" in result.uncertainties)
        val readonly = assess("env -u UNUSED cat /system/probe")
        assertEquals(setOf("READ:/system/probe"), readonly.targets)
        assertFalse(asks(readonly))
    }

    @Test fun hostWriteReviewRegression_fileCompileInShortOptionClusterIsNotReadonly() {
        for (command in listOf("file -bC -m /tmp/magic", "cd /system && file -bC -m /tmp/magic", "file -C -m /tmp/magic")) {
            val result = assess(command)
            assertTrue(command, asks(result))
            assertTrue(command, "opaque-code" in result.uncertainties)
        }
        assertFalse(asks(assess("file -b /system/probe")))
    }

    @Test fun hostWriteReviewRegression_linkSuffixValuesCannotBecomeSymbolicFlags() {
        for (command in listOf(
            "ln -Ssuffix /data/adb/source /data/local/tmp/link",
            "ln -S suffix /data/adb/source /data/local/tmp/link",
            "ln --suffix=suffix /data/adb/source /data/local/tmp/link",
            "ln --suffix suffix /data/adb/source /data/local/tmp/link"
        )) {
            val result = assess(command)
            assertEquals(command, setOf("READ_WRITE:/data/adb/source"), result.targets)
            assertTrue(command, result.uncertainties.isEmpty())
            assertTrue(command, asks(result))
        }
        for (command in listOf(
            "ln -sSsuffix /data/adb/source /data/local/tmp/link",
            "ln -s -S suffix /data/adb/source /data/local/tmp/link",
            "cp -Slink /data/adb/source /data/local/tmp/copy"
        )) assertFalse(command, asks(assess(command)))
    }

    @Test fun hostWriteReviewRegression_fileOptionValuesCannotBecomeCompileFlags() {
        for (command in listOf(
            "file -FC /system/probe", "file -F C /system/probe",
            "file -F -C /system/probe", "file --separator=C /system/probe", "file -bFC /system/probe"
        )) {
            val result = assess(command)
            assertEquals(command, setOf("READ:/system/probe"), result.targets)
            assertTrue(command, result.uncertainties.isEmpty())
            assertFalse(command, asks(result))
        }
        assertTrue(asks(assess("file -bC -F C /system/probe")))
    }

    @Test fun hostWriteReviewRegression_commandInfoClustersDoNotExecuteQueriedNames() {
        for (command in listOf("command -pv sh -v", "command -pV sh -V", "command -pv sh")) {
            val result = assess(command)
            assertTrue(command, result.targets.isEmpty())
            assertTrue(command, result.uncertainties.isEmpty())
            assertFalse(command, asks(result))
        }
        assertTrue(asks(assess("command -p sh -c 'touch /system/probe' -v")))
        assertTrue(asks(assess("command -pv sh >/system/probe"))) // Metadata still has output redirection.
    }

    @Test fun repositoryAuditRegression_clusteredTargetDirectoryConsumesSeparateAndAttachedValues() {
        for ((program, cluster) in listOf("cp" to "-vt", "mv" to "-vt", "install" to "-vt", "ln" to "-st")) {
            for ((namespace, destination) in listOf(Namespace.HOST to "/data/adb", Namespace.CHROOT_GUEST to "/sys/probe")) {
                for (option in listOf("$cluster $destination", "$cluster$destination", "--target-directory=$destination")) {
                    for (sources in listOf("/data/local/tmp/one", "/data/local/tmp/one /data/local/tmp/two")) {
                        val command = "$program $option $sources"
                        val result = assess(command, namespace)
                        assertEquals(command, setOf("WRITE:$destination"), result.targets)
                        assertTrue(command, result.uncertainties.isEmpty())
                        assertTrue(command, asks(result))
                    }
                }
            }
        }
    }

    @Test fun repositoryAuditRegression_targetDirectoryKeepsEverySourceDirection() {
        for ((program, sourceAccess) in listOf("cp" to "READ", "install" to "READ", "mv" to "WRITE", "ln" to "READ_WRITE")) {
            for (option in listOf("-vt /data/adb", "-vt/data/adb", "-t /data/adb", "--target-directory /data/adb")) {
                for (sources in listOf(listOf("/system/one"), listOf("/system/one", "/system/two"))) {
                    val command = "$program $option ${sources.joinToString(" ")}"
                    val result = assess(command)
                    val expected = sources.map { "$sourceAccess:$it" }.toSet() + "WRITE:/data/adb"
                    assertEquals(command, expected, result.targets)
                    assertTrue(command, result.uncertainties.isEmpty())
                    assertTrue(command, asks(result))
                }
            }
        }
        for (option in listOf("-st /data/adb", "-st/data/adb", "-s --target-directory=/data/adb")) {
            val command = "ln $option /system/one /system/two"
            val result = assess(command)
            assertEquals(command, setOf("WRITE:/data/adb"), result.targets)
            assertTrue(command, result.uncertainties.isEmpty())
            assertTrue(command, asks(result))
        }
    }

    @Test fun repositoryAuditRegression_readCopiesAndSymbolicLinkTextRemainAllowed() {
        for (program in listOf("cp", "install")) {
            for (option in listOf("-vt /tmp", "-vt/tmp", "--target-directory=/tmp")) {
                val command = "$program $option /system/one /system/two"
                val result = assess(command)
                assertEquals(command, setOf("READ:/system/one", "READ:/system/two"), result.targets)
                assertTrue(command, result.uncertainties.isEmpty())
                assertFalse(command, asks(result))
            }
        }
        for (command in listOf("cat /system/read", "cp /system/read /tmp/copy")) {
            val result = assess(command)
            assertEquals(command, setOf("READ:/system/read"), result.targets)
            assertTrue(command, result.uncertainties.isEmpty())
            assertFalse(command, asks(result))
        }
        for (command in listOf(
            "ln -st /tmp /system/one /system/two", "ln -st/tmp /system/one /system/two",
            "ln -s --target-directory=/tmp \"\$SOURCE\"", "ln -s /system/source /tmp/link"
        )) {
            val result = assess(command)
            assertTrue(command, result.targets.isEmpty())
            assertTrue(command, result.uncertainties.isEmpty())
            assertFalse(command, asks(result))
        }
        val guest = assess("cp -vt/usr/local /etc/one /etc/two", Namespace.CHROOT_GUEST)
        assertTrue(guest.targets.isEmpty())
        assertTrue(guest.uncertainties.isEmpty())
        assertFalse(asks(guest))
    }

    @Test fun repositoryAuditRegression_optionValuesNeverBecomeFlagsOrExtraFiles() {
        for (command in listOf(
            "install -vm0755 -votestuser -vgstaff -vSstrip -vt/tmp /system/source",
            "install -vm 0755 -vo testuser -vg staff -vS strip -vt /tmp /system/source",
            "install --mode=0755 --owner=testuser --group=staff --suffix=strip --target-directory=/tmp /system/source",
            "cp -vSlink /system/source /tmp/copy", "cp -vS -t /system/source /tmp/copy"
        )) {
            val result = assess(command)
            assertEquals(command, setOf("READ:/system/source"), result.targets)
            assertTrue(command, result.uncertainties.isEmpty())
            assertFalse(command, asks(result))
        }
        for (option in listOf("-vSsymbolic", "-vS symbolic", "--suffix=symbolic")) {
            val command = "ln $option /system/source /tmp/link"
            val result = assess(command)
            assertEquals(command, setOf("READ_WRITE:/system/source"), result.targets)
            assertTrue(command, result.uncertainties.isEmpty())
            assertTrue(command, asks(result))
        }
        for (command in listOf("file -bF-C /system/source", "file -bF -C /system/source")) {
            val result = assess(command)
            assertEquals(command, setOf("READ:/system/source"), result.targets)
            assertTrue(command, result.uncertainties.isEmpty())
            assertFalse(command, asks(result))
        }
    }

    @Test fun repositoryAuditRegression_endOfOptionsAndFlagLikeValuesKeepTheirOwnRoles() {
        for (command in listOf("cp -vt /tmp -- /system/source", "cp -vt/tmp -- /system/source")) {
            val result = assess(command)
            assertEquals(command, setOf("READ:/system/source"), result.targets)
            assertTrue(command, result.uncertainties.isEmpty())
            assertFalse(command, asks(result))
        }
        val copiedFlag = assess("cd /system && cp -- -vt /tmp/copy")
        assertEquals(setOf("READ:/system/-vt"), copiedFlag.targets)
        assertTrue(copiedFlag.uncertainties.isEmpty())
        assertFalse(asks(copiedFlag))
        val linkedFlag = assess("cd /system && ln -- -st /tmp/link")
        assertEquals(setOf("READ_WRITE:/system/-st"), linkedFlag.targets)
        assertTrue(linkedFlag.uncertainties.isEmpty())
        assertTrue(asks(linkedFlag))
        val symbolicFlag = assess("ln -s -- -t /tmp/link")
        assertTrue(symbolicFlag.targets.isEmpty())
        assertTrue(symbolicFlag.uncertainties.isEmpty())
        assertFalse(asks(symbolicFlag))
        // This -- is the suffix value, so the following -vt still selects a destination.
        val consumedDelimiter = assess("cp -vS -- -vt /data/adb /system/source")
        assertEquals(setOf("READ:/system/source", "WRITE:/data/adb"), consumedDelimiter.targets)
        assertTrue(consumedDelimiter.uncertainties.isEmpty())
        assertTrue(asks(consumedDelimiter))
    }

    @Test fun repositoryAuditRegression_referenceValuesStayReadOnlyInsideClusters() {
        for (command in listOf(
            "touch -cr /system/reference /tmp/output", "touch -cr/system/reference /tmp/output",
            "chmod --reference=/system/reference /tmp/output",
            "file -bm /system/reference /tmp/input", "file -bm/system/reference /tmp/input"
        )) {
            val result = assess(command)
            assertEquals(command, setOf("READ:/system/reference"), result.targets)
            assertTrue(command, result.uncertainties.isEmpty())
            assertFalse(command, asks(result))
        }
        for (option in listOf("-cr /system/reference", "-cr/system/reference")) {
            val result = assess("touch $option /data/adb/output")
            assertEquals(setOf("READ:/system/reference", "WRITE:/data/adb/output"), result.targets)
            assertTrue(result.uncertainties.isEmpty())
            assertTrue(asks(result))
        }
    }

    @Test fun repositoryAuditRegression_unknownAndMissingOptionsRemainUncertain() {
        for (program in listOf("cp", "mv", "ln", "install")) {
            for (option in listOf("-vZ", "--unmodelled-option")) {
                val command = "$program $option /tmp/source /tmp/destination"
                val result = assess(command)
                assertTrue(command, result.targets.isEmpty())
                assertTrue(command, "unknown-option-syntax" in result.uncertainties)
                assertTrue(command, asks(result))
            }
            for (option in listOf("-vt", "--target-directory", "-vS")) {
                val command = "$program /tmp/one /tmp/two $option"
                val result = assess(command)
                assertTrue(command, result.targets.isEmpty())
                assertTrue(command, "missing-option-operand" in result.uncertainties)
                assertTrue(command, asks(result))
            }
        }
        for (option in listOf("-vm", "-vo", "-vg")) {
            val command = "install /tmp/one /tmp/two $option"
            val result = assess(command)
            assertTrue(command, "missing-option-operand" in result.uncertainties)
            assertTrue(command, asks(result))
        }
        for (program in listOf("cp", "mv", "install", "ln")) {
            val flags = if (program == "ln") "-st" else "-vt"
            for (command in listOf("$program $flags /tmp", "$program ${flags}/tmp")) {
                val result = assess(command)
                assertTrue(command, result.targets.isEmpty())
                assertTrue(command, "missing-source-operand" in result.uncertainties)
                assertTrue(command, asks(result))
            }
        }
        assertFalse(asks(assess("install -d /tmp/new-directory")))
        val suffix = assess("cp -vS/extra /system/source /tmp/copy")
        assertEquals(setOf("READ:/system/source"), suffix.targets)
        assertTrue("backup-write-path" in suffix.uncertainties)
        assertTrue(asks(suffix))
    }

    @Test fun returnedTargetSetCannotBeMutatedAndSeparateClassificationsDoNotShareState() {
        val captured = targets("cat /system/probe")
        assertThrows(UnsupportedOperationException::class.java) { (captured as MutableSet<String>).clear() }
        assertTrue(targets("id").isEmpty())
        assertEquals(setOf("READ:/system/probe"), captured)
    }
}
