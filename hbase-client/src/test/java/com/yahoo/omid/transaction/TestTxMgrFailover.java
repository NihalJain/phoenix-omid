package com.yahoo.omid.transaction;

import static com.yahoo.omid.committable.hbase.HBaseCommitTable.COMMIT_TABLE_DEFAULT_NAME;
import static com.yahoo.omid.committable.hbase.HBaseCommitTable.COMMIT_TABLE_FAMILY;
import static com.yahoo.omid.committable.hbase.HBaseCommitTable.LOW_WATERMARK_FAMILY;
import static com.yahoo.omid.tsoclient.TSOClient.TSO_HOST_CONFKEY;
import static com.yahoo.omid.tsoclient.TSOClient.TSO_PORT_CONFKEY;
import static org.apache.hadoop.hbase.HConstants.HBASE_CLIENT_RETRIES_NUMBER;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;

import javax.annotation.Nullable;

import org.apache.commons.configuration.BaseConfiguration;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.SettableFuture;
import com.yahoo.omid.TestUtils;
import com.yahoo.omid.committable.CommitTable;
import com.yahoo.omid.committable.hbase.HBaseCommitTable;
import com.yahoo.omid.committable.hbase.HBaseCommitTableConfig;
import com.yahoo.omid.committable.CommitTable.CommitTimestamp;
import static com.yahoo.omid.committable.CommitTable.CommitTimestamp.Location.COMMIT_TABLE;
import com.yahoo.omid.transaction.Transaction.Status;
import com.yahoo.omid.tso.ProgrammableTSOServer;
import com.yahoo.omid.tsoclient.TSOClient;

public class TestTxMgrFailover {

    private static final Logger LOG = LoggerFactory.getLogger(TestTxMgrFailover.class);

    private static final long TX1_ST = 1L;
    private static final long TX1_CT = 2L;

    private static final byte[] table = Bytes.toBytes("test-table");
    private static final byte[] family = Bytes.toBytes("test-family");
    private static final byte[] qualifier = Bytes.toBytes("test-qual");
    private static final byte[] row1 = Bytes.toBytes("row1");
    private static final byte[] row2 = Bytes.toBytes("row2");
    private static final byte[] data1 = Bytes.toBytes("testWrite-1");
    private static final byte[] data2 = Bytes.toBytes("testWrite-2");

    // Tables involved
    private HTable testTable;
    private HTable commitTable;

    // HBase cluster-related data
    protected static HBaseTestingUtility hBaseUtils;
    private static MiniHBaseCluster hBaseCluster;
    private Configuration hbaseClusterConfig;

    // Spyable components
    private TSOClient tsoClientForTM;
    private CommitTable.Client commitTableClient;
    private CommitTable.Writer commitTableWriter;

    // Allows to prepare the required responses to client request operations
    private ProgrammableTSOServer tso;

    // The transaction manager under test
    private HBaseTransactionManager tm;

    @BeforeClass
    public void beforeClass() throws Exception {

        LOG.info("++++++++++++++++++ Starting HBase ++++++++++++++++++++++++");

        Configuration hbaseConf = HBaseConfiguration.create();
        hbaseConf.setInt("hbase.hregion.memstore.flush.size", 10_000 * 1024);
        hbaseConf.setInt("hbase.regionserver.nbreservationblocks", 1);
        hBaseUtils = new HBaseTestingUtility(hbaseConf);
        hBaseCluster = hBaseUtils.startMiniCluster(3);
        hBaseCluster.waitForActiveAndReadyMaster();
        hbaseClusterConfig = hBaseCluster.getConfiguration();

        LOG.info("+++++++++++++++++++ HBase started ++++++++++++++++++++++++");

        LOG.info("===================== Starting TSO  ======================");
        tso = new ProgrammableTSOServer(1234);
        TestUtils.waitForSocketListening("localhost", 1234, 100);
        LOG.info("================ Finished loading TSO ====================");

    }

    @AfterClass
    public void afterClass() throws Exception {

        hBaseUtils.shutdownMiniCluster();

    }

    @BeforeMethod
    public void setup() throws Exception {

        Thread.currentThread().setName("Test Thread");

        // Clear the status of the programmable tso server
        tso.cleanResponses();

        // Create commit table
        commitTable = hBaseUtils.createTable(Bytes.toBytes(COMMIT_TABLE_DEFAULT_NAME),
                new byte[][] { COMMIT_TABLE_FAMILY, LOW_WATERMARK_FAMILY }, Integer.MAX_VALUE);
        // Create tables for test
        testTable = hBaseUtils.createTable(table, new byte[][] { family }, Integer.MAX_VALUE);

        LOG.info("============ Getting Commit Table accessors ==============");
        CommitTable commitTable = new HBaseCommitTable(hbaseClusterConfig, new HBaseCommitTableConfig());
        commitTableClient = spy(commitTable.getClient().get());
        commitTableWriter = spy(commitTable.getWriter().get());
        LOG.info("============= Commit Table accessors obtained ============");

        LOG.info("===================== Starting TM =====================");
        Configuration hbaseConf = hBaseCluster.getConfiguration();
        hbaseConf.setInt(HBASE_CLIENT_RETRIES_NUMBER, 3);
        BaseConfiguration clientConf = new BaseConfiguration();
        clientConf.setProperty(TSO_HOST_CONFKEY, "localhost");
        clientConf.setProperty(TSO_PORT_CONFKEY, 1234);
        tsoClientForTM = spy(TSOClient.newBuilder().withConfiguration(clientConf).build());

        tm = spy(HBaseTransactionManager.newBuilder()
                                        .withTSOClient(tsoClientForTM)
                                        .withCommitTableClient(commitTableClient)
                                        .withConfiguration(hbaseConf)
                                        .build());
        LOG.info("===================== TM Started =========================");

        LOG.info("++++++++++++++++++ Starting Experiment... ++++++++++++++++");
    }

    @AfterMethod
    public void cleanup() throws Exception {
        hBaseUtils.deleteTable(table);
        hBaseUtils.deleteTable(COMMIT_TABLE_DEFAULT_NAME);
    }

    @Test
    public void testAbortResponseFromTSOThrowsRollbackExceptionInClient() throws Exception {

        // Program the TSO to return an ad-hoc Timestamp and an abort response for tx 1
        tso.queueResponse(new ProgrammableTSOServer.TimestampResponse(TX1_ST));
        tso.queueResponse(new ProgrammableTSOServer.AbortResponse(TX1_ST));

        try (TTable txTable = new TTable(hBaseCluster.getConfiguration(), table)) {

            HBaseTransaction tx1 = (HBaseTransaction) tm.begin();
            assertEquals(tx1.getStartTimestamp(), TX1_ST);
            Put put = new Put(row1);
            put.add(family, qualifier, data1);
            txTable.put(tx1, put);
            assertEquals(hBaseUtils.countRows(testTable), 1, "Rows should be 1!");
            checkOperationSuccessOnCell(KeyValue.Type.Put, data1, table, row1, family, qualifier);

            try {
                tm.commit(tx1);
                fail();
            } catch (RollbackException e) {
                // Expected!

            }

            // Check transaction status
            assertEquals(tx1.getStatus(), Status.ROLLEDBACK);
            assertEquals(tx1.getCommitTimestamp(), 0);
            // Check the cleanup process did its job and the committed data is NOT there
            checkOperationSuccessOnCell(KeyValue.Type.Delete, null, table, row1, family, qualifier);
        }

    }

    @Test
    public void testClientReceivesSuccessfulCommitForNonInvalidatedTxCommittedByPreviousTSO() throws Exception {

        // Program the TSO to return an ad-hoc Timestamp and an commit response with heuristic actions
        tso.queueResponse(new ProgrammableTSOServer.TimestampResponse(TX1_ST));
        tso.queueResponse(new ProgrammableTSOServer.CommitResponse(true, TX1_ST, TX1_CT));
        // Simulate that tx1 was committed by writing to commit table
        commitTableWriter.addCommittedTransaction(TX1_ST, TX1_CT);
        commitTableWriter.flush();
        assertEquals(hBaseUtils.countRows(commitTable), 1, "Rows should be 1!");

        try (TTable txTable = new TTable(hBaseCluster.getConfiguration(), table)) {

            HBaseTransaction tx1 = (HBaseTransaction) tm.begin();
            assertEquals(tx1.getStartTimestamp(), TX1_ST);
            Put put = new Put(row1);
            put.add(family, qualifier, data1);
            txTable.put(tx1, put);
            // Should succeed
            tm.commit(tx1);

            // Check transaction status
            assertEquals(tx1.getStatus(), Status.COMMITTED);
            assertEquals(tx1.getCommitTimestamp(), TX1_CT);
            // Check the cleanup process did its job and the committed data is there
            // Note that now we do not clean up the commit table when exercising the heuristic actions
            assertEquals(hBaseUtils.countRows(commitTable), 1,
                    "Rows should be 1! We don't have to clean CT in this case");
            Optional<CommitTimestamp> optionalCT = tm.commitTableClient.getCommitTimestamp(TX1_ST).get();
            assertTrue(optionalCT.isPresent());
            checkOperationSuccessOnCell(KeyValue.Type.Put, data1, table, row1, family, qualifier);
        }

    }

    @Test
    public void testClientReceivesRollbackExceptionForInvalidatedTxCommittedByPreviousTSO() throws Exception {

        // Program the TSO to return an ad-hoc Timestamp and a commit response with heuristic actions
        tso.queueResponse(new ProgrammableTSOServer.TimestampResponse(TX1_ST));
        tso.queueResponse(new ProgrammableTSOServer.CommitResponse(true, TX1_ST, TX1_CT));
        // Simulate that tx1 was committed by writing to commit table but was later invalidated
        commitTableClient.tryInvalidateTransaction(TX1_ST);
        assertEquals(hBaseUtils.countRows(commitTable), 1, "Rows should be 1!");

        try (TTable txTable = new TTable(hBaseCluster.getConfiguration(), table)) {

            HBaseTransaction tx1 = (HBaseTransaction) tm.begin();
            assertEquals(tx1.getStartTimestamp(), TX1_ST);
            Put put = new Put(row1);
            put.add(family, qualifier, data1);
            txTable.put(tx1, put);
            try {
                tm.commit(tx1);
                fail();
            } catch (RollbackException e) {
                // Exception
            }

            // Check transaction status
            assertEquals(tx1.getStatus(), Status.ROLLEDBACK);
            assertEquals(tx1.getCommitTimestamp(), 0);
            // Check the cleanup process did its job and the uncommitted data is NOT there
            assertEquals(hBaseUtils.countRows(commitTable), 1, "Rows should be 1! Dirty data should be there");
            Optional<CommitTimestamp> optionalCT = tm.commitTableClient.getCommitTimestamp(TX1_ST).get();
            assertTrue(optionalCT.isPresent());
            assertFalse(optionalCT.get().isValid());
            checkOperationSuccessOnCell(KeyValue.Type.Delete, null, table, row1, family, qualifier);
        }

    }

    @Test
    public void testClientReceivesNotificationOfANewTSOCanInvalidateTransaction() throws Exception {

        // Program the TSO to return an ad-hoc Timestamp and a commit response with heuristic actions
        tso.queueResponse(new ProgrammableTSOServer.TimestampResponse(TX1_ST));
        tso.queueResponse(new ProgrammableTSOServer.CommitResponse(true, TX1_ST, TX1_CT));

        assertEquals(hBaseUtils.countRows(commitTable), 0, "Rows should be 0!");

        try (TTable txTable = new TTable(hBaseCluster.getConfiguration(), table)) {

            HBaseTransaction tx1 = (HBaseTransaction) tm.begin();
            assertEquals(tx1.getStartTimestamp(), TX1_ST);
            Put put = new Put(row1);
            put.add(family, qualifier, data1);
            txTable.put(tx1, put);
            try {
                tm.commit(tx1);
                fail();
            } catch (RollbackException e) {
                // Expected
            }

            // Check transaction status
            assertEquals(tx1.getStatus(), Status.ROLLEDBACK);
            assertEquals(tx1.getCommitTimestamp(), 0);
            // Check the cleanup process did its job and the transaction was invalidated
            // Uncommitted data should NOT be there
            assertEquals(hBaseUtils.countRows(commitTable), 1, "Rows should be 1! Dirty data should be there");
            Optional<CommitTimestamp> optionalCT = tm.commitTableClient.getCommitTimestamp(TX1_ST).get();
            assertTrue(optionalCT.isPresent());
            assertFalse(optionalCT.get().isValid());
            checkOperationSuccessOnCell(KeyValue.Type.Delete, null, table, row1, family, qualifier);
        }

    }

    @Test
    public void testClientSuccessfullyCommitsWhenReceivingNotificationOfANewTSOAandCANTInvalidateTransaction()
            throws Exception {

        // Program the TSO to return an ad-hoc Timestamp and a commit response with heuristic actions
        tso.queueResponse(new ProgrammableTSOServer.TimestampResponse(TX1_ST));
        tso.queueResponse(new ProgrammableTSOServer.CommitResponse(true, TX1_ST, TX1_CT));

        // Simulate that the original TSO was able to add the tx to commit table in the meantime
        commitTableWriter.addCommittedTransaction(TX1_ST, TX1_CT);
        commitTableWriter.flush();
        assertEquals(hBaseUtils.countRows(commitTable), 1, "Rows should be 1!");
        SettableFuture<Optional<CommitTimestamp>> f1 = SettableFuture.<Optional<CommitTimestamp>> create();
        f1.set(Optional.<CommitTimestamp> absent());
        SettableFuture<Optional<CommitTimestamp>> f2 = SettableFuture.<Optional<CommitTimestamp>> create();
        f2.set(Optional.of(new CommitTimestamp(COMMIT_TABLE, TX1_CT, true)));
        doReturn(f1).doReturn(f2).when(commitTableClient).getCommitTimestamp(TX1_ST);

        try (TTable txTable = new TTable(hBaseCluster.getConfiguration(), table)) {

            HBaseTransaction tx1 = (HBaseTransaction) tm.begin();
            assertEquals(tx1.getStartTimestamp(), TX1_ST);
            Put put = new Put(row1);
            put.add(family, qualifier, data1);
            txTable.put(tx1, put);

            tm.commit(tx1);

            // Check transaction status
            assertEquals(tx1.getStatus(), Status.COMMITTED);
            assertEquals(tx1.getCommitTimestamp(), TX1_CT);
            // Check the cleanup process did its job and the committed data is there
            // Note that now we do not clean up the commit table when exercising the heuristic actions
            assertEquals(hBaseUtils.countRows(commitTable), 1,
                    "Rows should be 1! We don't have to clean CT in this case");
            checkOperationSuccessOnCell(KeyValue.Type.Put, data1, table, row1, family, qualifier);
        }

    }

    @Test
    public void testClientReceivesATransactionExceptionWhenReceivingNotificationOfANewTSOAndCANTInvalidateTransactionAndCTCheckIsUnsuccessful()
            throws Exception {

        // Program the TSO to return an ad-hoc Timestamp and a commit response with heuristic actions
        tso.queueResponse(new ProgrammableTSOServer.TimestampResponse(TX1_ST));
        tso.queueResponse(new ProgrammableTSOServer.CommitResponse(true, TX1_ST, TX1_CT));

        // Simulate that the original TSO was able to add the tx to commit table in the meantime
        SettableFuture<Boolean> f = SettableFuture.<Boolean> create();
        f.set(false);
        doReturn(f).when(commitTableClient).tryInvalidateTransaction(TX1_ST);

        assertEquals(hBaseUtils.countRows(commitTable), 0, "Rows should be 0!");

        try (TTable txTable = new TTable(hBaseCluster.getConfiguration(), table)) {

            HBaseTransaction tx1 = (HBaseTransaction) tm.begin();
            assertEquals(tx1.getStartTimestamp(), TX1_ST);
            Put put = new Put(row1);
            put.add(family, qualifier, data1);
            txTable.put(tx1, put);
            try {
                tm.commit(tx1);
                fail();
            } catch (TransactionException e) {
                // Expected but is not good because we're not able to determine the tx outcome
            }

            // Check transaction status
            assertEquals(tx1.getStatus(), Status.RUNNING);
            assertEquals(tx1.getCommitTimestamp(), 0);
        }

    }

    // **************************** Helpers ***********************************

    protected void checkOperationSuccessOnCell(KeyValue.Type targetOp, @Nullable byte[] expectedValue,
            byte[] tableName,
            byte[] row,
            byte[] fam,
            byte[] col) {

        try (HTable table = new HTable(hbaseClusterConfig, tableName)) {
            Get get = new Get(row).setMaxVersions(1);
            Result result = table.get(get);
            Cell latestCell = result.getColumnLatestCell(fam, col);

            switch (targetOp) {
            case Put:
                assertEquals(latestCell.getTypeByte(), targetOp.getCode());
                assertEquals(CellUtil.cloneValue(latestCell), expectedValue);
                LOG.trace("Value for " + Bytes.toString(tableName) + ":"
                        + Bytes.toString(row) + ":" + Bytes.toString(fam) + ":"
                        + Bytes.toString(col) + "=>" + Bytes.toString(CellUtil.cloneValue(latestCell))
                        + " (" + Bytes.toString(expectedValue) + " expected)");
                break;
            case Delete:
                LOG.trace("Value for " + Bytes.toString(tableName) + ":"
                        + Bytes.toString(row) + ":" + Bytes.toString(fam)
                        + Bytes.toString(col) + " deleted");
                assertNull(latestCell);
                break;
            default:
                fail();
            }
        } catch (IOException e) {
            LOG.error("Error reading row " + Bytes.toString(tableName) + ":"
                    + Bytes.toString(row) + ":" + Bytes.toString(fam)
                    + Bytes.toString(col), e);
            fail();
        }
    }

}
