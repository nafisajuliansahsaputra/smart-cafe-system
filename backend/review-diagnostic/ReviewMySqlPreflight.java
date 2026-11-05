import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * No Spring, no Flyway, no DDL and no customer data read.
 * Aiven-only synthetic review diagnostic executed after TLS CA import.
 */
public final class ReviewMySqlPreflight {
    private static final String PREFIX = "SCS_REVIEW_SCHEMA_READ_ONLY";

    public static void main(String[] args) throws Exception {
        String url=System.getenv("DB_URL");
        String user=System.getenv("DB_USERNAME");
        String password=System.getenv("DB_PASSWORD");
        String ack=System.getenv("SCS_MYSQL_REVIEW_ACK");
        if (url==null || user==null || password==null || password.isBlank()
            || !"SCS_MYSQL_SYNTHETIC_REVIEW_ONLY".equals(ack)
            || !url.matches("^jdbc:mysql://[a-zA-Z0-9.-]+\\.aivencloud\\.com:[0-9]+/scs_review_[a-z0-9_]{4,32}\\?.*$")
            || !url.contains("sslMode=VERIFY_IDENTITY")
            || !url.contains("trustCertificateKeyStoreUrl=file:/tmp/scs-aiven-truststore.p12")) {
            throw new IllegalStateException("Isolated Aiven review preflight rejected");
        }
        DriverManager.setLoginTimeout(9);
        try (Connection connection=DriverManager.getConnection(url,user,password)) {
            connection.setReadOnly(true);
            long historyTable = scalar(connection,
                "SELECT COUNT(*) FROM information_schema.tables "
                +"WHERE table_schema=DATABASE() AND table_name='flyway_schema_history'");
            long receiptsTable=scalar(connection,
                "SELECT COUNT(*) FROM information_schema.tables "
                +"WHERE table_schema=DATABASE() AND table_name='receipts'");
            long receiptUpdateTrigger=scalar(connection,
                "SELECT COUNT(*) FROM information_schema.triggers "
                +"WHERE trigger_schema=DATABASE() AND trigger_name='receipt_no_update'");
            long receiptDeleteTrigger=scalar(connection,
                "SELECT COUNT(*) FROM information_schema.triggers "
                +"WHERE trigger_schema=DATABASE() AND trigger_name='receipt_no_delete'");
            long orderSnapshotTrigger=scalar(connection,
                "SELECT COUNT(*) FROM information_schema.triggers "
                +"WHERE trigger_schema=DATABASE() AND trigger_name='order_snapshot_no_update'");
            long receiptsRows=receiptsTable==0?0:scalar(connection,
                "SELECT COUNT(*) FROM receipts");
            long countedBusinessTables=0;
            long nonEmptyBusinessTables=0;
            try (PreparedStatement names=connection.prepareStatement(
                    "SELECT table_name FROM information_schema.tables "
                    +"WHERE table_schema=DATABASE() AND table_type='BASE TABLE' "
                    +"AND table_name NOT IN ('flyway_schema_history','staff_roles')");
                 ResultSet tables=names.executeQuery()) {
                while(tables.next()) {
                    String table=tables.getString(1);
                    if (!table.matches("[a-z][a-z0-9_]*")) {
                        throw new IllegalStateException("Unexpected review SQL table identifier");
                    }
                    countedBusinessTables++;
                    if (scalar(connection,"SELECT COUNT(*) FROM `"+table+"`")>0) {
                        nonEmptyBusinessTables++;
                    }
                }
            }
            long applied=historyTable==0?0:scalar(connection,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1");
            long failed=historyTable==0?0:scalar(connection,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0");
            long failedV4=historyTable==0?0:scalar(connection,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='4' AND success=0");
            System.out.printf(
                "%s historyTable=%d applied=%d failed=%d failedV4=%d receipts=%d receiptRows=%d businessTables=%d nonEmptyBusinessTables=%d receiptUpdateTrigger=%d receiptDeleteTrigger=%d orderSnapshotTrigger=%d%n",
                PREFIX,historyTable,applied,failed,failedV4,receiptsTable,
                receiptsRows,countedBusinessTables,nonEmptyBusinessTables,
                receiptUpdateTrigger,receiptDeleteTrigger,orderSnapshotTrigger);
        } catch (SQLException ex) {
            // Never print a driver exception: it can contain the whole JDBC URL.
            System.err.println(PREFIX+" connection_or_read_error");
            throw new IllegalStateException("Read-only review schema inspection failed");
        }
    }

    private static long scalar(Connection connection,String sql) throws SQLException {
        try (PreparedStatement statement=connection.prepareStatement(sql);
             ResultSet rs=statement.executeQuery()) {
            if (!rs.next()) throw new SQLException("Scalar query returned no row");
            return rs.getLong(1);
        }
    }
}
