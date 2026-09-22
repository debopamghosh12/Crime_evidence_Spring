package com.blockevidence.backend.dto;

/** F2 Q3: the plaintext file's name/type/size, decrypted from the metadata document just far enough for the
 *  controller to set HTTP headers before it starts writing the (also decrypted) body bytes to the response. */
public record FileDescriptor(String fileName, String contentType, long size) {
}
