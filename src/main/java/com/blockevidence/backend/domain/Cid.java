package com.blockevidence.backend.domain;

import java.util.regex.Pattern;

/**
 * Syntax check for IPFS content identifiers. Applied before a CID is put into a request to the IPFS
 * node (so a malformed value can never inject extra query parameters) and by the in-memory ledger.
 * Accepts CIDv0 ("Qm" + 44 base58 chars) and CIDv1 in lowercase base32 ("b..."), which is what
 * IpfsClient produces. It checks shape only, not that the content exists.
 */
public final class Cid {

    private static final Pattern VALID = Pattern.compile("^(Qm[1-9A-HJ-NP-Za-km-z]{44}|b[a-z2-7]{50,120})$");

    private Cid() {
    }

    public static boolean isValid(String cid) {
        return cid != null && VALID.matcher(cid).matches();
    }
}
