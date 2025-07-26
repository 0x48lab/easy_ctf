package com.hacklab.ctf.utils

enum class MatchMode(val displayName: String) {
    FIXED_ROUNDS("固定回数モード");
    
    companion object {
        fun fromString(value: String): MatchMode? {
            return values().find { it.name.equals(value, ignoreCase = true) }
        }
    }
}