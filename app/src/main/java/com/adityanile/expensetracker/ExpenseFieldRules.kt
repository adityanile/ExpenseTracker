package com.adityanile.expensetracker

object ExpenseFieldRules {

    private const val EXCLUDED_FIELD_PAYER_BANK_NAME = "PayerBankName"

    fun isExcludedFieldKey(key: String): Boolean {
        val normalized = key.replace("_", "")
        return normalized.equals(EXCLUDED_FIELD_PAYER_BANK_NAME, ignoreCase = true)
    }
}
