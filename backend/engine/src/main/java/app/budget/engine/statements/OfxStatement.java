package app.budget.engine.statements;

import app.budget.engine.Money;
import app.budget.engine.statements.StatementRead.Kind;
import app.budget.engine.statements.StatementText.DateOrder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A statement downloaded as OFX or QFX (what Quicken and most banks' "download transactions" offer). Read here without a
 * library: only the transaction list is wanted, and both the older SGML form (tags left unclosed) and the XML form keep
 * each value right after its tag.
 *
 * <p>OFX writes every amount from the account holder's side: money spent is negative, on a card as on a bank account.
 */
public final class OfxStatement {

    private OfxStatement() {}

    /** The balance at the end of the statement, as the bank reckons it. */
    private static final Pattern LEDGER_BALANCE = Pattern.compile("<LEDGERBAL>(.*?)(?:</LEDGERBAL>|(?=<AVAILBAL>)|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TRANSACTION = Pattern.compile("<STMTTRN>(.*?)(?:</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** True for text that is an OFX file, whichever form. */
    public static boolean looksLikeOfx(String text) {
        String start = text.substring(0, Math.min(text.length(), 4096)).toUpperCase(Locale.ROOT);
        return start.contains("OFXHEADER") || start.contains("<OFX>");
    }

    /**
     * @param onCard true for a credit card's statement, for guessing what each line is
     */
    public static StatementRead read(String text, boolean onCard) {
        List<StatementRead.Line> lines = new ArrayList<>();
        Matcher transactions = TRANSACTION.matcher(text);
        while (transactions.find() && lines.size() < CsvStatement.MAX_LINES) {
            String block = transactions.group(1);
            Optional<LocalDate> posted = value(block, "DTPOSTED").flatMap(day -> StatementText.date(day, DateOrder.YMD));
            Optional<LocalDate> used = value(block, "DTUSER").flatMap(day -> StatementText.date(day, DateOrder.YMD));
            Optional<Money> amount = value(block, "TRNAMT").flatMap(StatementText::amount);
            if (posted.isEmpty() && used.isEmpty() || amount.isEmpty()) {
                continue;
            }
            String name = value(block, "NAME").orElse("");
            String memo = value(block, "MEMO").orElse("");
            String description = name.isBlank() ? memo : memo.isBlank() || memo.equalsIgnoreCase(name) ? name : name + " " + memo;
            String words = description.isBlank() ? "(no description)" : unescape(description);
            Money spent = amount.get().negate();
            Kind kind = kind(value(block, "TRNTYPE").orElse(""), words, spent, onCard);
            LocalDate txnDate = used.orElse(posted.orElse(null));
            lines.add(new StatementRead.Line(
                    txnDate,
                    used.isPresent() ? posted.orElse(null) : null,
                    words,
                    spent,
                    kind,
                    true,
                    value(block, "FITID").orElse(null)));
        }
        LocalDate from = value(text, "DTSTART").flatMap(day -> StatementText.date(day, DateOrder.YMD)).orElse(null);
        LocalDate to = value(text, "DTEND").flatMap(day -> StatementText.date(day, DateOrder.YMD)).orElse(null);
        // OFX gives only the closing balance. Written from the holder's side, a card's debt is negative: kept as what is owed.
        Matcher ledger = LEDGER_BALANCE.matcher(text);
        Money closing = ledger.find() ? value(ledger.group(1), "BALAMT").flatMap(StatementText::amount).orElse(null) : null;
        return StatementRead.of(lines, from, to).withBalances(null, closing == null ? null : onCard ? closing.negate() : closing);
    }

    /** The type the bank gave settles payments and transfers; the words settle the rest. */
    private static Kind kind(String type, String words, Money spent, boolean onCard) {
        String upper = type.trim().toUpperCase(Locale.ROOT);
        if (upper.equals("XFER") || upper.equals("DIRECTDEP")) {
            return Kind.TRANSFER;
        }
        if (onCard && upper.equals("PAYMENT") && spent.isNegative()) {
            return Kind.PAYMENT;
        }
        return LineKinds.guess(words, spent, onCard);
    }

    /** The text after {@code <TAG>}, up to the next tag or the end of the line. */
    private static Optional<String> value(String block, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + ">([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(block);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String value = matcher.group(1).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static String unescape(String text) {
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
    }
}
