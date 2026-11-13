package dev.bytebank.notifications.notification;

import dev.bytebank.notifications.event.MovementEvent;

import java.util.Locale;

/**
 * Texto del aviso que ve el cliente.
 */
final class NotificationMessages {

    private static final Locale ES = Locale.forLanguageTag("es-ES");

    private NotificationMessages() {
    }

    public static String of(MovementEvent event) {
        String formattedAmount = formatAmount(Math.abs(event.amount()));
        String account = buildAccountDisplay(event.iban());
        String concept = buildConceptDisplay(event.concept());
        String message = buildMessageByType(event.type(), formattedAmount, account, concept);
        return message + ". Saldo: " + formatAmount(event.balanceAfter());
    }

    private static String buildAccountDisplay(String iban) {
        return "tu cuenta ···" + iban.substring(iban.length() - 4);
    }

    private static String buildConceptDisplay(String concept) {
        if (concept == null || concept.isBlank()) return "";
        return " (" + concept + ")";
    }

    private static String buildMessageByType(String type, String amount, String account, String concept) {
        return switch (type) {
            case "INGRESO" -> "Ingreso de " + amount + " en " + account + concept;
            case "TRANSFERENCIA_EMITIDA" -> "Has enviado " + amount + " desde " + account + concept;
            case "TRANSFERENCIA_RECIBIDA" -> "Has recibido " + amount + " en " + account + concept;
            case "PAGO_TARJETA" -> "Pago con tarjeta de " + amount + " en " + account + concept;
            default -> "Movimiento de " + amount + " en " + account + concept;
        };
    }

    private static String formatAmount(double value) {
        return String.format(ES, "%,.2f €", value);
    }
}
