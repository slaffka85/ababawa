package com.abavava.chat.call;

/**
 * Куда писать принятые события телеметрии. Абстракция нужна, чтобы сервис
 * можно было протестировать без перехвата {@code System.out}.
 */
public interface TelemetrySink {

    /** Пишет одну готовую NDJSON-строку (без завершающего перевода строки). */
    void write(String ndjsonLine);
}
