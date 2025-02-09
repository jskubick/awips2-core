/**
 * This software was developed and / or modified by Raytheon Company,
 * pursuant to Contract DG133W-05-CQ-1067 with the US Government.
 * 
 * U.S. EXPORT CONTROLLED TECHNICAL DATA
 * This software product contains export-restricted data whose
 * export/transfer/disclosure is restricted by U.S. law. Dissemination
 * to non-U.S. persons whether in the United States or abroad requires
 * an export license or other authorization.
 * 
 * Contractor Name:        Raytheon Company
 * Contractor Address:     6825 Pine Street, Suite 340
 *                         Mail Stop B8
 *                         Omaha, NE 68106
 *                         402.291.0100
 * 
 * See the AWIPS II Master Rights File ("Master Rights File.pdf") for
 * further licensing information.
 **/
package com.raytheon.uf.edex.database.health;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.SQLQuery;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;
import org.hibernate.type.IntegerType;
import org.hibernate.type.StringType;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import com.raytheon.uf.edex.database.dao.CoreDao;
import com.raytheon.uf.edex.database.dao.DaoConfig;

/**
 * Postgres implemetation of Database Bloat checking.
 * 
 * <pre>
 * 
 * SOFTWARE HISTORY
 * 
 * Date         Ticket#    Engineer    Description
 * ------------ ---------- ----------- --------------------------
 * Feb 10, 2016 4630       rjpeter     Initial creation
 * Jun 20, 2016 5679       rjpeter     Add admin database account
 * Sep 08, 2017 DR 20135   D. Friedman Rebuild constraint-backing indexes concurrently
 * 
 * </pre>
 * 
 * @author rjpeter
 * @version 1.0
 */

public class PostgresBloatDao extends CoreDao implements BloatDao {
    /**
     * Pulled from github. Modified to only retrieve schema, table, real size,
     * bloat size, and bloat percent, and to exclude system tables / indexes.
     * 
     * https://github.com/ioguix/pgsql-bloat-estimation
     * 
     * This query is compatible with PostgreSQL 9.0 and more
     * 
     * <pre>
     * SELECT schemaname, tblname, bs*tblpages AS real_size, (tblpages-est_tblpages_ff)*bs AS bloat_size,
     *   CASE WHEN tblpages - est_tblpages_ff > 0
     *     THEN 100 * (tblpages - est_tblpages_ff)/tblpages::float
     *     ELSE 0
     *   END AS bloat_ratio
     * FROM (
     *   SELECT ceil( reltuples / ( (bs-page_hdr)/tpl_size ) ) + ceil( toasttuples / 4 ) AS est_tblpages,
     *     ceil( reltuples / ( (bs-page_hdr)*fillfactor/(tpl_size*100) ) ) + ceil( toasttuples / 4 ) AS est_tblpages_ff,
     *     tblpages, fillfactor, bs, tblid, schemaname, tblname, heappages, toastpages, is_na
     *   FROM (
     *     SELECT
     *       ( 4 + tpl_hdr_size + tpl_data_size + (2*ma)
     *         - CASE WHEN tpl_hdr_size%ma = 0 THEN ma ELSE tpl_hdr_size%ma END
     *         - CASE WHEN ceil(tpl_data_size)::int%ma = 0 THEN ma ELSE ceil(tpl_data_size)::int%ma END
     *       ) AS tpl_size, bs - page_hdr AS size_per_block, (heappages + toastpages) AS tblpages, heappages,
     *       toastpages, reltuples, toasttuples, bs, page_hdr, tblid, schemaname, tblname, fillfactor, is_na
     *     FROM (
     *       SELECT
     *         tbl.oid AS tblid, ns.nspname AS schemaname, tbl.relname AS tblname, tbl.reltuples,
     *         tbl.relpages AS heappages, coalesce(toast.relpages, 0) AS toastpages,
     *         coalesce(toast.reltuples, 0) AS toasttuples,
     *         coalesce(substring(
     *           array_to_string(tbl.reloptions, ' ')
     *           FROM '%fillfactor=#"__#"%' FOR '#')::smallint, 100) AS fillfactor,
     *         current_setting('block_size')::numeric AS bs,
     *         CASE WHEN version()~'mingw32' OR version()~'64-bit|x86_64|ppc64|ia64|amd64' THEN 8 ELSE 4 END AS ma,
     *         24 AS page_hdr,
     *         23 + CASE WHEN MAX(coalesce(null_frac,0)) > 0 THEN ( 7 + count(*) ) / 8 ELSE 0::int END
     *           + CASE WHEN tbl.relhasoids THEN 4 ELSE 0 END AS tpl_hdr_size,
     *         sum( (1-coalesce(s.null_frac, 0)) * coalesce(s.avg_width, 1024) ) AS tpl_data_size,
     *         bool_or(att.atttypid = 'pg_catalog.name'::regtype) AS is_na
     *       FROM pg_attribute AS att
     *         JOIN pg_class AS tbl ON att.attrelid = tbl.oid
     *         JOIN pg_namespace AS ns ON ns.oid = tbl.relnamespace
     *         JOIN pg_stats AS s ON s.schemaname=ns.nspname
     *           AND s.tablename = tbl.relname AND s.inherited=false AND s.attname=att.attname
     *         LEFT JOIN pg_class AS toast ON tbl.reltoastrelid = toast.oid
     *       WHERE att.attnum > 0 AND NOT att.attisdropped
     *         AND tbl.relkind = 'r'
     *         AND ns.nspname NOT IN ('pg_catalog', 'information_schema') 
     *       GROUP BY 1,2,3,4,5,6,7,8,9,10, tbl.relhasoids
     *       ORDER BY 2,3
     *     ) AS s
     *   ) AS s2
     * ) AS s3
     * WHERE NOT is_na 
     * order by 1, 2;
     * </pre>
     */
    private static final String TABLE_BLOAT_SQL = "SELECT schemaname, tblname, bs*(tblpages)::bigint AS real_size, (tblpages-est_tblpages_ff)*bs AS bloat_size, "
            + "CASE WHEN tblpages - est_tblpages_ff > 0 THEN 100 * (tblpages - est_tblpages_ff)/tblpages::float "
            + "ELSE 0 END AS bloat_ratio FROM ("
            + "SELECT ceil( reltuples / ( (bs-page_hdr)/tpl_size ) ) + ceil( toasttuples / 4 ) AS est_tblpages, "
            + "ceil( reltuples / ( (bs-page_hdr)*fillfactor/(tpl_size*100) ) ) + ceil( toasttuples / 4 ) AS est_tblpages_ff, "
            + "tblpages, fillfactor, bs, tblid, schemaname, tblname, heappages, toastpages, is_na "
            + "FROM ( SELECT ( 4 + tpl_hdr_size + tpl_data_size + (2*ma) "
            + "- CASE WHEN tpl_hdr_size%ma = 0 THEN ma ELSE tpl_hdr_size%ma END "
            + "- CASE WHEN ceil(tpl_data_size)::int%ma = 0 THEN ma ELSE ceil(tpl_data_size)::int%ma END "
            + ") AS tpl_size, bs - page_hdr AS size_per_block, (heappages + toastpages) AS tblpages, heappages, "
            + "toastpages, reltuples, toasttuples, bs, page_hdr, tblid, schemaname, tblname, fillfactor, is_na "
            + "FROM ( SELECT tbl.oid AS tblid, ns.nspname AS schemaname, tbl.relname AS tblname, tbl.reltuples, "
            + "tbl.relpages AS heappages, coalesce(toast.relpages, 0) AS toastpages, "
            + "coalesce(toast.reltuples, 0) AS toasttuples, coalesce(substring(array_to_string(tbl.reloptions, ' ') "
            + "FROM '%fillfactor=#\"__#\"%' FOR '#')::smallint, 100) AS fillfactor, current_setting('block_size')::numeric AS bs, "
            + "CASE WHEN version()~'mingw32' OR version()~'64-bit|x86_64|ppc64|ia64|amd64' THEN 8 ELSE 4 END AS ma, "
            + "24 AS page_hdr, 23 + CASE WHEN MAX(coalesce(null_frac,0)) > 0 THEN ( 7 + count(*) ) / 8 ELSE 0::int END "
            + "+ CASE WHEN tbl.relhasoids THEN 4 ELSE 0 END AS tpl_hdr_size, "
            + "sum( (1-coalesce(s.null_frac, 0)) * coalesce(s.avg_width, 1024) ) AS tpl_data_size, "
            + "bool_or(att.atttypid = 'pg_catalog.name'::regtype) AS is_na "
            + "FROM pg_attribute AS att JOIN pg_class AS tbl ON att.attrelid = tbl.oid "
            + "JOIN pg_namespace AS ns ON ns.oid = tbl.relnamespace "
            + "JOIN pg_stats AS s ON s.schemaname=ns.nspname "
            + "AND s.tablename = tbl.relname AND s.inherited=false AND s.attname=att.attname "
            + "LEFT JOIN pg_class AS toast ON tbl.reltoastrelid = toast.oid "
            + "WHERE att.attnum > 0 AND NOT att.attisdropped  AND tbl.relkind = 'r' AND ns.nspname NOT IN ('pg_catalog', 'information_schema') "
            + "GROUP BY 1,2,3,4,5,6,7,8,9,10, tbl.relhasoids ORDER BY 2,3) AS s) AS s2) AS s3 WHERE NOT is_na order by 1, 2";

    /**
     * Pulled from github. Modified to only retrieve schema, table, index, real
     * size, bloat size, and bloat percent, and to exclude system tables /
     * indexes.
     * 
     * https://github.com/ioguix/pgsql-bloat-estimation
     * 
     * This query is compatible with PostgreSQL 9.0 and more
     * 
     * <pre>
     * SELECT nspname AS schemaname, tblname, idxname, bs*(relpages)::bigint AS real_size,
     *   bs*(relpages-est_pages_ff) AS bloat_size,
     *   100 * (relpages-est_pages_ff)::float / relpages AS bloat_ratio
     * FROM (
     *   SELECT coalesce(1 +
     *        ceil(reltuples/floor((bs-pageopqdata-pagehdr)/(4+nulldatahdrwidth)::float)), 0 -- ItemIdData size + computed avg size of a tuple (nulldatahdrwidth)
     *     ) AS est_pages,
     *     coalesce(1 +
     *        ceil(reltuples/floor((bs-pageopqdata-pagehdr)*fillfactor/(100*(4+nulldatahdrwidth)::float))), 0
     *     ) AS est_pages_ff,
     *     bs, nspname, table_oid, tblname, idxname, relpages, fillfactor, is_na
     *   FROM (
     *     SELECT maxalign, bs, nspname, tblname, idxname, reltuples, relpages, relam, table_oid, fillfactor,
     *       ( index_tuple_hdr_bm +
     *           maxalign - CASE -- Add padding to the index tuple header to align on MAXALIGN
     *             WHEN index_tuple_hdr_bm%maxalign = 0 THEN maxalign
     *             ELSE index_tuple_hdr_bm%maxalign
     *           END
     *         + nulldatawidth + maxalign - CASE -- Add padding to the data to align on MAXALIGN
     *             WHEN nulldatawidth = 0 THEN 0
     *             WHEN nulldatawidth::integer%maxalign = 0 THEN maxalign
     *             ELSE nulldatawidth::integer%maxalign
     *           END
     *       )::numeric AS nulldatahdrwidth, pagehdr, pageopqdata, is_na
     *     FROM (
     *       SELECT
     *         i.nspname, i.tblname, i.idxname, i.reltuples, i.relpages, i.relam, a.attrelid AS table_oid,
     *         current_setting('block_size')::numeric AS bs, fillfactor,
     *         CASE -- MAXALIGN: 4 on 32bits, 8 on 64bits (and mingw32 ?)
     *           WHEN version() ~ 'mingw32' OR version() ~ '64-bit|x86_64|ppc64|ia64|amd64' THEN 8
     *           ELSE 4
     *         END AS maxalign,
     *         -- per page header, fixed size: 20 for 7.X, 24 for others
     *         24 AS pagehdr,
     *         -- per page btree opaque data
     *         16 AS pageopqdata,
     *         -- per tuple header: add IndexAttributeBitMapData if some cols are null-able
     *         CASE WHEN max(coalesce(s.null_frac,0)) = 0
     *           THEN 2 -- IndexTupleData size
     *           ELSE 2 + (( 32 + 8 - 1 ) / 8) -- IndexTupleData size + IndexAttributeBitMapData size ( max num filed per index + 8 - 1 /8)
     *         END AS index_tuple_hdr_bm,
     *         -- data len: we remove null values save space using it fractionnal part from stats
     *         sum( (1-coalesce(s.null_frac, 0)) * coalesce(s.avg_width, 1024)) AS nulldatawidth,
     *         max( CASE WHEN a.atttypid = 'pg_catalog.name'::regtype THEN 1 ELSE 0 END ) > 0 AS is_na
     *       FROM pg_attribute AS a
     *         JOIN (
     *           SELECT nspname, tbl.relname AS tblname, idx.relname AS idxname, idx.reltuples, idx.relpages, idx.relam,
     *             indrelid, indexrelid, indkey::smallint[] AS attnum,
     *             coalesce(substring(
     *               array_to_string(idx.reloptions, ' ')
     *                from 'fillfactor=([0-9]+)')::smallint, 90) AS fillfactor
     *           FROM pg_index
     *             JOIN pg_class idx ON idx.oid=pg_index.indexrelid
     *             JOIN pg_class tbl ON tbl.oid=pg_index.indrelid
     *             JOIN pg_namespace ON pg_namespace.oid = idx.relnamespace
     *           WHERE pg_index.indisvalid AND tbl.relkind = 'r' AND idx.relpages > 0 AND nspname NOT IN ('pg_catalog', 'information_schema')
     *         ) AS i ON a.attrelid = i.indexrelid
     *         JOIN pg_stats AS s ON s.schemaname = i.nspname
     *           AND ((s.tablename = i.tblname AND s.attname = pg_catalog.pg_get_indexdef(a.attrelid, a.attnum, TRUE)) -- stats from tbl
     *           OR   (s.tablename = i.idxname AND s.attname = a.attname))-- stats from functionnal cols
     *         JOIN pg_type AS t ON a.atttypid = t.oid
     *       WHERE a.attnum > 0
     *       GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9
     *     ) AS s1
     *   ) AS s2
     *     JOIN pg_am am ON s2.relam = am.oid WHERE am.amname = 'btree'
     * ) AS sub
     * WHERE NOT is_na
     * ORDER BY 1, 2, 3;
     * </pre>
     */
    private static final String INDEX_BLOAT_SQL = "SELECT nspname AS schemaname, tblname, idxname, bs*(relpages)::bigint AS real_size,  bs*(relpages-est_pages_ff) AS bloat_size, "
            + "100 * (relpages-est_pages_ff)::float / relpages AS bloat_ratio FROM (SELECT coalesce(1 + "
            + "ceil(reltuples/floor((bs-pageopqdata-pagehdr)/(4+nulldatahdrwidth)::float)), 0) AS est_pages, coalesce(1 + ceil(reltuples/floor((bs-pageopqdata-pagehdr)*fillfactor/(100* "
            + "(4+nulldatahdrwidth)::float))), 0) AS est_pages_ff, bs, nspname, table_oid, tblname, "
            + "idxname, relpages, fillfactor, is_na FROM (SELECT maxalign, bs, nspname, tblname, "
            + "idxname, reltuples, relpages, relam, table_oid, fillfactor, ( index_tuple_hdr_bm + "
            + "maxalign - CASE WHEN index_tuple_hdr_bm%maxalign = 0 THEN maxalign ELSE "
            + "index_tuple_hdr_bm%maxalign END + nulldatawidth + maxalign - CASE WHEN nulldatawidth = 0 "
            + "THEN 0 WHEN nulldatawidth::integer%maxalign = 0 THEN maxalign ELSE "
            + "nulldatawidth::integer%maxalign END)::numeric AS nulldatahdrwidth, pagehdr, pageopqdata, "
            + "is_na FROM (SELECT i.nspname, i.tblname, i.idxname, i.reltuples, i.relpages, i.relam, "
            + "a.attrelid AS table_oid, current_setting('block_size')::numeric AS bs, fillfactor, CASE "
            + "WHEN version() ~ 'mingw32' OR version() ~ '64-bit|x86_64|ppc64|ia64|amd64' THEN 8 "
            + "ELSE 4 END AS maxalign, 24 AS pagehdr, 16 AS pageopqdata, CASE WHEN max( "
            + "coalesce(s.null_frac,0)) = 0 THEN 2 ELSE 2 + (( 32 + 8 - 1 ) / 8) END AS "
            + "index_tuple_hdr_bm, sum( (1-coalesce(s.null_frac, 0)) * coalesce(s.avg_width, 1024)) AS "
            + "nulldatawidth, max( CASE WHEN a.atttypid = 'pg_catalog.name'::regtype THEN 1 ELSE 0 END ) "
            + "> 0 AS is_na FROM pg_attribute AS a JOIN (SELECT nspname, tbl.relname AS tblname, "
            + "idx.relname AS idxname, idx.reltuples, idx.relpages, idx.relam, indrelid, indexrelid, "
            + "indkey::smallint[] AS attnum, coalesce(substring(array_to_string(idx.reloptions, ' ') "
            + "from 'fillfactor=([0-9]+)')::smallint, 90) AS fillfactor FROM pg_index JOIN pg_class idx "
            + "ON idx.oid=pg_index.indexrelid JOIN pg_class tbl ON tbl.oid=pg_index.indrelid JOIN "
            + "pg_namespace ON pg_namespace.oid = idx.relnamespace WHERE pg_index.indisvalid AND "
            + "tbl.relkind = 'r' AND idx.relpages > 0 AND nspname NOT IN ('pg_catalog', 'information_schema')) "
            + "AS i ON a.attrelid = i.indexrelid JOIN pg_stats "
            + "AS s ON s.schemaname = i.nspname AND ((s.tablename = i.tblname AND s.attname = "
            + "pg_catalog.pg_get_indexdef(a.attrelid, a.attnum, TRUE)) OR (s.tablename = i.idxname AND "
            + "s.attname = a.attname)) JOIN pg_type AS t ON a.atttypid = t.oid WHERE a.attnum > 0 "
            + "GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9) AS s1) AS s2 JOIN pg_am am ON s2.relam = am.oid WHERE "
            + "am.amname = 'btree') AS sub WHERE NOT is_na  ORDER BY 1, 2, 3;";

    /**
     * For a given name (table/constraint/index) in a schema, grab the oid and index definition.
     */
    private static final String SELECT_INDEX_INFO = "select tbl.oid, pg_get_indexdef(tbl.oid) as indexdef "
            + "from pg_class tbl join pg_namespace ns "
            + "on ns.oid = tbl.relnamespace where tbl.relname = :name and ns.nspname = :schema";

    /**
     * For a given name, check if it is a constraint.
     */
    private static final String SELECT_CONSTRAINT_INFO = "select con.oid, con.contype "
            + "from pg_constraint con join pg_namespace ns "
            + "on ns.oid = con.connamespace where con.conname = :name and ns.nspname = :schema";

    /**
     * Fetch foreign constraints that use the same index as the given constraint.
     */
    private static final String SELECT_INDEX_FCONS = "select "
            + "fcon.conname as fconname, fconrel.relname as fconrelname, fconns.nspname as fconns, "
            + "fcon.contype as fcontype, pg_get_constraintdef(fcon.oid) as fcondef "
            + "from pg_constraint fcon join pg_class fconrel on fcon.conrelid = fconrel.oid "
            + "join pg_namespace fconns on fcon.connamespace = fconns.oid "
            + "where fcon.conindid = :conindid";

    private static final String SELECT_INDEX_FCONS_EXCLUSION = " and fcon.oid <> :conid";

    protected final Pattern INDEX_REGEX = Pattern
            .compile("(.+?) INDEX \"?.+?\"? (ON .+)");

    protected final String database;

    public PostgresBloatDao(String database) {
        super(DaoConfig.forDatabase(database, true));
        this.database = database;
    }

    @Override
    public String getDatabase() {
        return database;
    }

    @Override
    public List<TableBloat> getTableBloatData() {
        Object[] rows = executeSQLQuery(TABLE_BLOAT_SQL);
        List<TableBloat> rval = null;

        if (rows != null) {
            rval = new ArrayList<>(rows.length);
            for (Object row : rows) {
                Object[] cols = (Object[]) row;
                TableBloat info = new TableBloat();
                info.setSchema(String.valueOf(cols[0]));
                info.setTableName(String.valueOf(cols[1]));
                info.setRealSizeBytes(((Number) cols[2]).longValue());
                info.setBloatBytes(((Number) cols[3]).longValue());
                info.setBloatPercent(((Number) cols[4]).doubleValue());
                rval.add(info);
            }
        } else {
            rval = Collections.emptyList();
        }

        return rval;
    }

    @Override
    public List<IndexBloat> getIndexBloatData() {
        Object[] rows = executeSQLQuery(INDEX_BLOAT_SQL);
        List<IndexBloat> rval = null;

        if (rows != null) {
            rval = new ArrayList<>(rows.length);
            for (Object row : rows) {
                Object[] cols = (Object[]) row;
                IndexBloat info = new IndexBloat();
                info.setSchema(String.valueOf(cols[0]));
                info.setTableName(String.valueOf(cols[1]));
                info.setIndexName(String.valueOf(cols[2]));
                info.setRealSizeBytes(((Number) cols[3]).longValue());
                info.setBloatBytes(((Number) cols[4]).longValue());
                info.setBloatPercent(((Number) cols[5]).doubleValue());
                rval.add(info);
            }
        } else {
            rval = Collections.emptyList();
        }

        return rval;
    }

    @Override
    public void vacuumTable(TableBloat info) {
        throw new IllegalArgumentException("Not Implemented");
    }

    @Override
    public void reindex(final IndexBloat info) {
        txTemplate.execute(new TransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                String schema = info.getSchema();
                String indexName = info.getIndexName();
                String fqnIndexName = "\"" + schema + "\"" + ".\"" + indexName
                        + "\"";
                Map<String, Object> paramMap = new HashMap<>(2, 1);
                paramMap.put("name", indexName);
                paramMap.put("schema", schema);
                Session sess = getCurrentSession();

                SQLQuery query = sess.createSQLQuery(SELECT_INDEX_INFO)
                        .addScalar("oid", IntegerType.INSTANCE)
                        .addScalar("indexdef", StringType.INSTANCE);
                addParamsToQuery(query, paramMap);
                ScrollableResults queryResult = query.scroll();

                Integer indexId = null;
                String indexDef = null;
                if (queryResult.next()) {
                    indexId = queryResult.getInteger(0);
                    indexDef = queryResult.getString(1);
                }
                if (indexId == null || indexDef == null) {
                    logger.warn("Could not look up OID and definition for index: " + fqnIndexName);
                    return 0;
                }

                logger.info("Index definition: " + indexDef);

                /* update index name to a tmp name */
                String tmpName = "tmp_" + indexName;
                if (tmpName.length() > 64) {
                    tmpName.substring(0, 64);
                }
                String fqnTmpName = "\"" + schema + "\"" + ".\"" + tmpName
                        + "\"";

                /*
                 * check for previously created bad index and delete it if it
                 * exists
                 */
                sess.createSQLQuery("DROP INDEX IF EXISTS " + fqnTmpName)
                        .executeUpdate();

                Matcher matcher = INDEX_REGEX.matcher(indexDef);
                if (!matcher.matches()) {
                    logger.warn("Could not parse index definition.  Manual reindex required. Definition ["
                            + indexDef + "]");
                    return 0;
                }

                /*
                 * The index may be backing a primary key or unique constraint.
                 * Recreate the constraint if that's the case.
                 */
                query = sess.createSQLQuery(SELECT_CONSTRAINT_INFO)
                        .addScalar("oid", IntegerType.INSTANCE)
                        .addScalar("contype", StringType.INSTANCE);
                addParamsToQuery(query, paramMap);
                queryResult = query.scroll();

                boolean isConstraint = queryResult.next();
                String indexTypeForConstraint = null;
                if (isConstraint) {
                    String constraintType = queryResult.getString(1);
                    if ("p".equals(constraintType)) {
                        indexTypeForConstraint = "PRIMARY KEY";
                    } else if ("u".equals(constraintType)) {
                        indexTypeForConstraint = "UNIQUE";
                    } else {
                        logger.warn(String.format("Can not recreate index %s for constraint type '%s'",
                                indexName, constraintType));
                        return 0;
                    }
                }

                /*
                 * Recreate foreign constraints that depend on this index.
                 */
                query = sess.createSQLQuery(SELECT_INDEX_FCONS
                        + (isConstraint ? SELECT_INDEX_FCONS_EXCLUSION : ""))
                        .addScalar("fconname", StringType.INSTANCE)
                        .addScalar("fconrelname", StringType.INSTANCE)
                        .addScalar("fconns", StringType.INSTANCE)
                        .addScalar("fcontype", StringType.INSTANCE)
                        .addScalar("fcondef", StringType.INSTANCE);
                paramMap.clear();
                paramMap.put("conindid", indexId);
                if (isConstraint) {
                    Integer conId = queryResult.getInteger(0);
                    paramMap.put("conid", conId);
                }
                addParamsToQuery(query, paramMap);
                queryResult = query.scroll();

                StringBuilder fkconDrops = new StringBuilder();
                StringBuilder fkconAdds = new StringBuilder();
                StringBuilder fkconValidates = new StringBuilder();
                while (queryResult.next()) {
                    String fconType = queryResult.getString(3);
                    String fkConName = queryResult.getString(0);
                    if ("f".equals(fconType)) {
                        String fqnFkTable = String.format("\"%s\".\"%s\"", queryResult.getString(2), queryResult.getString(1));
                        fkconDrops.append(String.format("ALTER TABLE %s DROP CONSTRAINT %s;\n", fqnFkTable, fkConName));
                        fkconAdds.append(String.format("ALTER TABLE %s ADD CONSTRAINT %s %s NOT VALID;\n", fqnFkTable, fkConName, queryResult.getString(4)));
                        fkconValidates.append(String.format("ALTER TABLE %s VALIDATE CONSTRAINT %s;\n", fqnFkTable, fkConName));
                    } else {
                        logger.warn(String.format(
                                "Constraint %s appears to depend on %s, but do not know how to handle it. "
                                        + "Manually rebuilding the index is recommended",
                                fkConName, indexName));
                        return 0;
                    }
                }

                /*
                 * Create temp index concurrently, cannot happen in a
                 * transaction block
                 */
                String cmd = "COMMIT; " + matcher.group(1)
                        + " INDEX CONCURRENTLY \"" + tmpName + "\" "
                        + matcher.group(2);
                logger.info("Creating new index concurrently. Running cmd: "
                        + cmd);
                sess.createSQLQuery(cmd).executeUpdate();

                /*
                 * Recreate the index either by recreating the constraint(s) or
                 * by renaming the temporary index.
                 */
                StringBuilder recreateCmd = new StringBuilder();
                recreateCmd.append("BEGIN;\n")
                    .append(fkconDrops);
                if (isConstraint) {
                    recreateCmd.append("ALTER TABLE \"" + schema + "\".\"" + info.getTableName() + "\" "
                            + "DROP CONSTRAINT " + "\"" + indexName + "\", "
                            + "ADD CONSTRAINT \"" + indexName + "\" " + indexTypeForConstraint
                            + " USING INDEX \"" + tmpName + "\"");
                } else {
                    recreateCmd.append("DROP INDEX IF EXISTS " + fqnIndexName
                            + ";\nALTER INDEX " + fqnTmpName + " RENAME TO \"" + indexName + "\"");
                }
                recreateCmd.append(";\n")
                    .append(fkconAdds)
                    .append("COMMIT;\nBEGIN;\n")
                    .append(fkconValidates)
                    .append("COMMIT;\n");
                cmd = recreateCmd.toString();
                logger.info("Recreate/rename index: " + cmd);
                return sess.createSQLQuery(cmd).executeUpdate();
            }
        });

    }
}
