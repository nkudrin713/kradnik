package com.nkudrin713.kradnik.app

enum class AppEnvironment(val value: String) {
    PROD("prod"),
    TEST("test"),
    ;

    companion object {
        fun fromConfig(value: String): AppEnvironment {
            return entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("APP_ENV must be one of: prod, test")
        }
    }
}
