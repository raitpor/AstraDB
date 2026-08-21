package com.astradb.server.ingest;

import com.astradb.core.ingest.SnapshotData;
import com.astradb.core.ingest.SnapshotIngestor;
import com.astradb.core.meta.ColumnType;
import com.astradb.core.meta.Schema;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SS-5：CSV 未闭合引号必须报格式错误（RFC 4180），不得把后续行并入同一字段静默错数据；
 * 正常引号字段（含逗号/转义引号）与表头跳过保持可用。
 */
class CsvParserTest {

    private static final Schema SCHEMA = new Schema(1, List.of(
            new Schema.ColumnDef("id", ColumnType.INT, false),
            new Schema.ColumnDef("v", ColumnType.STRING, false)), 0);

    private static SnapshotData parse(String csv) throws Exception {
        try (InputStream in = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))) {
            return CsvParser.parse(in, SCHEMA, false);
        }
    }

    @Test
    void unclosedQuoteAtEolRejected() {
        // 引号内出现换行 = 未闭合引号 → 报错（修复前会把后续行并入同一字段）
        assertThrows(SnapshotIngestor.IngestException.class,
                () -> parse("1,\"a\n2,2.0\n"));
    }

    @Test
    void unclosedQuoteAtEofRejected() {
        // 文件在引号内结束 = 未闭合引号 → 报错
        assertThrows(SnapshotIngestor.IngestException.class,
                () -> parse("1,\"abc"));
    }

    @Test
    void quotedFieldWithCommaAndEscapedQuoteParsed() throws Exception {
        SnapshotData data = parse("1,\"a,b\"\n2,\"x\"\"y\"\n");
        assertEquals(2, data.rowCount());
        assertEquals("a,b", data.columns().get(1).strings()[0]);
        assertEquals("x\"y", data.columns().get(1).strings()[1]);
    }

    @Test
    void plainRowsAndTrailingNewlineParsed() throws Exception {
        SnapshotData data = parse("1,one\n2,two\n");
        assertEquals(2, data.rowCount());
        assertEquals("two", data.columns().get(1).strings()[1]);
    }
}
