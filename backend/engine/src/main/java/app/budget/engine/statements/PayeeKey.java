package app.budget.engine.statements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a payee's category and tags are remembered under (Rachel, 2026-10-25: remembered by payee). A statement words the
 * same shop differently from one visit to the next ("SQ *BLUE BOTTLE 0123", "BLUE BOTTLE #77"), so the key is the first
 * two words left once store numbers, card-processor prefixes and punctuation are gone: "blue bottle" for both. Two words
 * keep "whole foods" apart from "whole earth"; where the second word is a town ("kroger dayton", "kroger cincinnati"),
 * {@link #firstWord} lets a payee remembered in one town be found in the next.
 */
public final class PayeeKey {

    private PayeeKey() {}

    /** How many words make the key. */
    private static final int WORDS = 2;

    /**
     * Words that say how something was paid rather than who was paid: a point-of-sale prefix, a card processor
     * ("SQ *", "TST*"), a bank's own wording.
     */
    private static final Set<String> NOISE = Set.of(
            "pos", "debit", "purchase", "card", "checkcard", "check", "recurring", "sq", "tst", "pp", "sp", "dd", "ach",
            "visa", "mc", "the", "www", "com", "inc", "llc", "ref", "id", "web", "ppd", "pmt", "point", "of", "sale");

    /** The key for a line's description; empty for a description with no words in it at all. */
    public static String of(String description) {
        if (description == null) {
            return "";
        }
        // Store numbers, dates and amounts in a description change every time; letters are what stay.
        String letters = description.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+", " ").trim();
        List<String> kept = new ArrayList<>();
        for (String word : letters.split(" ")) {
            if (word.length() < 2 || NOISE.contains(word)) {
                continue;
            }
            kept.add(word);
            if (kept.size() == WORDS) {
                break;
            }
        }
        return String.join(" ", kept);
    }

    /** The first word of a key, for finding a payee remembered under another second word. */
    public static String firstWord(String key) {
        int space = key.indexOf(' ');
        return space < 0 ? key : key.substring(0, space);
    }
}
