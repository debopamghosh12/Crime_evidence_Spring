package com.blockevidence.backend.storage;

import java.io.IOException;
import java.io.InputStream;

/** Consumes a content stream inside {@link IpfsClient#read}; the client closes the stream afterwards. */
@FunctionalInterface
public interface ContentReader<T> {

    T read(InputStream content) throws IOException;
}
