package com.abavava.chat.call;

import org.springframework.stereotype.Component;

import java.io.PrintStream;

/**
 * Пишет телеметрию в stdout по строке на событие (NDJSON).
 *
 * <p>Намеренно без SLF4J-префиксов: строку можно сразу подать в Loki/Filebeat
 * или прогнать через {@code jq}. Остальные логи приложения идут рядом, поэтому
 * потребитель фильтрует строки, начинающиеся с «{».
 */
@Component
public class StdoutTelemetrySink implements TelemetrySink {

    @Override
    public void write(String ndjsonLine) {
        PrintStream out = System.out;
        // Синхронизация на самом потоке: строки не перемешиваются между собой,
        // даже если сервлет-потоки пишут одновременно.
        synchronized (out) {
            out.println(ndjsonLine);
            out.flush();
        }
    }
}
