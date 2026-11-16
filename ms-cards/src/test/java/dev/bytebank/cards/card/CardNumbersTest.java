package dev.bytebank.cards.card;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardNumbersTest {

    @Test
    @DisplayName("Genera números de 16 dígitos con prefijo de marca y Luhn válido")
    void generatesLuhnValidNumbers() {
        for (int i = 0; i < 200; i++) {
            String number = CardNumbers.generate();

            assertThat(number).hasSize(16).containsOnlyDigits().startsWith("4");
            assertThat(isValidLuhn(number)).as("Luhn de %s", number).isTrue();
        }
    }

    @Test
    @DisplayName("generateLast4 devuelve exactamente 4 dígitos")
    void generatesOnlyLast4() {
        for (int i = 0; i < 50; i++) {
            assertThat(CardNumbers.generateLast4()).hasSize(4).containsOnlyDigits();
        }
    }

    private static boolean isValidLuhn(String number) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = number.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
