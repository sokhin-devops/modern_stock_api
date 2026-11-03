package com.modernstock.common;

/** Cambodian phone numbers: 0XX XXX XXX(X) or +855 XX XXX XXX(X) (PLAIN.md section 4). */
public final class Phones {

    public static final String KH = "^(0|\\+855\\s?)\\d{2}\\s?\\d{3}\\s?\\d{3,4}$";

    private Phones() {
    }
}
