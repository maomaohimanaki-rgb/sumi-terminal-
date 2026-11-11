package com.termux.terminal

object JNI {
    val isLoaded: Boolean = try {
        System.loadLibrary("termux")
        true
    } catch (_: Throwable) {
        false
    }

    @JvmStatic
    external fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envVars: Array<String>,
        processIdArray: IntArray,
        rows: Int,
        columns: Int
    ): Int

    @JvmStatic
    external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int)

    @JvmStatic
    external fun setPtyUTF8Mode(fd: Int)

    @JvmStatic
    external fun waitFor(processId: Int): Int

    @JvmStatic
    external fun close(fileDescriptor: Int)
}
