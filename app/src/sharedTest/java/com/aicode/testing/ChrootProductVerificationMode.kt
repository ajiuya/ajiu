package com.aicode.testing

internal object ChrootProductVerificationMode {
    const val SUITE = "com.aicode.testing.ChrootProductDeviceSuite"
    const val MODE = "product-broker-v1"
    const val SCOPE = "chroot-product-offline-v1"
    const val FD_COUNT = 5
    const val PRODUCT_COUNT = 6
    const val TOTAL_COUNT = FD_COUNT + PRODUCT_COUNT

    fun checkArguments(arguments: Map<String, String?>) {
        require(arguments.keys == setOf("class", "mode", "confirmedScope"))
        require(arguments["class"] == SUITE && arguments["mode"] == MODE && arguments["confirmedScope"] == SCOPE)
    }

    fun countsMatch(fd: Int, product: Int, started: Int, finished: Int): Boolean =
        fd == FD_COUNT && product == PRODUCT_COUNT && started == TOTAL_COUNT && finished == TOTAL_COUNT
}
