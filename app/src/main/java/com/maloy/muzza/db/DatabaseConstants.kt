package com.maloy.muzza.db

// SQLite rejects IN clauses with more variables than this (999 by default on older Android
// versions); keep one slot of headroom.
const val SQLITE_MAX_VARIABLES = 900
