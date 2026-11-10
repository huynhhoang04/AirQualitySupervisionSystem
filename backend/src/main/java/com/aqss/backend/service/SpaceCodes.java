package com.aqss.backend.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;

/**
 * A room name typed by a person, turned into a {@code spaces.code}.
 *
 * <p>Vietnamese names arrive with diacritics, spaces, and sometimes punctuation. The code has to be
 * stable, short (the column is 20 chars) and readable in a URL, so it is the ASCII skeleton of the
 * name; a name with nothing usable in it falls back to a time-based code rather than failing.
 */
@Component
public class SpaceCodes {

    public String derive(String label) {
        String slug = Normalizer.normalize(label == null ? "" : label, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")                 // strip diacritics
                .replaceAll("[đĐ]", "d")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "SP_" + Long.toString(System.currentTimeMillis() % 100_000, 36).toUpperCase(Locale.ROOT);
        }
        return slug.length() <= 20 ? slug : slug.substring(0, 20);
    }
}
