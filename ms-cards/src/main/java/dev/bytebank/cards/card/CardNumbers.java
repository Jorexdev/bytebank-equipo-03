package dev.bytebank.cards.card;

import java.security.SecureRandom;

/** Genera números de tarjeta de 16 dígitos con dígito de control Luhn. Solo se persisten los 4 últimos. */
public final class CardNumbers {

    private static final int LENGTH = 16;
    private static final char PREFIX = '4';
    private static final SecureRandom RANDOM = new SecureRandom();

    private CardNumbers() {
    }

    /** Genera un número completo. No debe persistirse ni devolverse: usar {@link #generateLast4()}. */
    static String generate() {
        StringBuilder digits = new StringBuilder(LENGTH - 1);
        digits.append(PREFIX);
        for (int i = 1; i < LENGTH - 1; i++) {
            digits.append(RANDOM.nextInt(10));
        }
        return digits.append(luhnCheckDigit(digits)).toString();
    }

    /** Devuelve los 4 últimos dígitos del número generado. */
    public static String generateLast4() {
        String number = generate();
        return number.substring(number.length() - 4);
    }

    private static char luhnCheckDigit(CharSequence digits) {
        int sum = 0;
        boolean doubleDigit = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }
}
