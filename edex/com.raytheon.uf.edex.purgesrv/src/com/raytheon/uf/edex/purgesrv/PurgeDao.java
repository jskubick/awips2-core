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
package com.raytheon.uf.edex.purgesrv;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.hibernate.Query;
import org.hibernate.Session;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;

import com.raytheon.uf.edex.database.dao.CoreDao;
import com.raytheon.uf.edex.database.dao.DaoConfig;

/**
 * 
 * Data access object for accessing purge job status objects
 * 
 * <pre>
 * 
 * SOFTWARE HISTORY
 * 
 * Date         Ticket#    Engineer    Description
 * ------------ ---------- ----------- --------------------------
 * May 1, 2012  #470       bphillip    Initial creation
 * Jun 24, 2014 #3314      randerso    Fix type safety warnings
 * 10/16/2014   3454       bphillip    Upgrading to Hibernate 4
 * 10/28/2014   3454       bphillip    Fix usage of getSession()
 * Aug 05, 2015 4486       rjpeter     Changed Timestamp to Date.
 * </pre>
 * 
 * @author bphillip
 * @version 1.0
 */
public class PurgeDao extends CoreDao {

    /**
     * Constructs a new purge data access object
     */
    public PurgeDao() {
        super(DaoConfig.forClass(PurgeJobStatus.class));
    }

    /**
     * Gets the number of purge jobs currently running on the cluster. A job is
     * considered running if the 'running' flag is set to true and the job has
     * been started since validStartTime and has not met or exceeded the failed
     * count.
     * 
     * @param validStartTime
     * @param failedCount
     * @return The number of purge jobs currently running on the cluster
     */
    public int getRunningClusterJobs(final Date validStartTime,
            final int failedCount) {
        final String query = "from "
                + daoClass.getName()
                + " obj where obj.running = true and obj.startTime > :startTime and obj.failedCount <= :failedCount";
        return txTemplate.execute(new TransactionCallback<Integer>() {
            @Override
            public Integer doInTransaction(TransactionStatus status) {
                Query hibQuery = getCurrentSession().createQuery(query);
                hibQuery.setTimestamp("startTime", validStartTime);
                hibQuery.setInteger("failedCount", failedCount);
                List<?> queryResult = hibQuery.list();
                if (queryResult == null) {
                    return 0;
                } else {
                    return queryResult.size();
                }
            }
        });
    }

    /**
     * Returns the jobs that have met or exceed the failed count.
     * 
     * @param failedCount
     * @return
     */
    @SuppressWarnings("unchecked")
    public List<PurgeJobStatus> getFailedJobs(final int failedCount) {
        final String query = "from " + daoClass.getName()
                + " obj where obj.failedCount >= :failedCount";
        return txTemplate
                .execute(new TransactionCallback<List<PurgeJobStatus>>() {
                    @Override
                    public List<PurgeJobStatus> doInTransaction(
                            TransactionStatus status) {
                        Query hibQuery = getCurrentSession().createQuery(query);
                        hibQuery.setInteger("failedCount", failedCount);
                        return hibQuery.list();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    public List<PurgeJobStatus> getTimedOutJobs(final Date validStartTime) {
        final String query = "from "
                + daoClass.getName()
                + " obj where obj.running = true and obj.startTime <= :startTime";
        return txTemplate
                .execute(new TransactionCallback<List<PurgeJobStatus>>() {
                    @Override
                    public List<PurgeJobStatus> doInTransaction(
                            TransactionStatus status) {
                        Query hibQuery = getCurrentSession().createQuery(query);
                        hibQuery.setTimestamp("startTime", validStartTime);
                        return hibQuery.list();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    public Map<String, List<PurgeJobStatus>> getRunningServerJobs() {
        final String query = "from "
                + daoClass.getName()
                + " obj where obj.running = true and obj.timedOut = false and obj.failed = false and obj.id.server=':SERVER'";
        return txTemplate
                .execute(new TransactionCallback<Map<String, List<PurgeJobStatus>>>() {
                    @Override
                    public Map<String, List<PurgeJobStatus>> doInTransaction(
                            TransactionStatus status) {
                        Map<String, List<PurgeJobStatus>> serverMap = new HashMap<String, List<PurgeJobStatus>>();
                        Query serverQuery = getCurrentSession().createQuery(
                                "select distinct obj.id.server from "
                                        + daoClass.getName()
                                        + " obj order by obj.id.server asc");
                        List<String> result = serverQuery.list();
                        for (String server : result) {
                            Query query2 = getCurrentSession().createQuery(
                                    query.replace(":SERVER", server));
                            serverMap.put(server, query2.list());
                        }
                        return serverMap;
                    }
                });
    }

    /**
     * Gets the amount of time in milliseconds since the last purge of a given
     * plugin
     * 
     * @param plugin
     *            The plugin name
     * @return Number of milliseconds since the purge job was run for the given
     *         plugin
     */
    public long getTimeSinceLastPurge(String plugin) {
        final String query = "select obj.startTime from " + daoClass.getName()
                + " obj where obj.id.plugin='" + plugin + "'";
        return txTemplate.execute(new TransactionCallback<Long>() {
            @Override
            public Long doInTransaction(TransactionStatus status) {
                Query hibQuery = getCurrentSession().createQuery(query);
                Date queryResult = (Date) hibQuery.uniqueResult();
                if (queryResult == null) {
                    return -1L;
                } else {
                    return System.currentTimeMillis() - queryResult.getTime();
                }
            }
        });
    }

    /**
     * Gets the purge job status for a plugin
     * 
     * @param plugin
     *            The plugin to get the purge job status for
     * @return The purge job statuses
     */
    public PurgeJobStatus getJobForPlugin(String plugin) {
        final String query = "from " + daoClass.getName()
                + " obj where obj.id.plugin='" + plugin + "'";
        return txTemplate.execute(new TransactionCallback<PurgeJobStatus>() {
            @Override
            public PurgeJobStatus doInTransaction(TransactionStatus status) {
                Query hibQuery = getCurrentSession().createQuery(query);
                PurgeJobStatus queryResult = (PurgeJobStatus) hibQuery
                        .uniqueResult();
                return queryResult;
            }
        });
    }

    /**
     * Sets a purge job to running status and sets the startTime to current
     * time. If was previously running, will increment the failed count.
     * 
     * @param plugin
     *            The plugin row to update
     */
    public void startJob(final String plugin) {
        final String query = "from " + daoClass.getName()
                + " obj where obj.id.plugin='" + plugin + "'";
        txTemplate.execute(new TransactionCallback<PurgeJobStatus>() {
            @Override
            public PurgeJobStatus doInTransaction(TransactionStatus status) {
                Session sess = getCurrentSession();
                Query hibQuery = sess.createQuery(query);
                PurgeJobStatus queryResult = (PurgeJobStatus) hibQuery
                        .uniqueResult();
                if (queryResult == null) {
                    queryResult = new PurgeJobStatus();
                    queryResult.setFailedCount(0);
                    queryResult.setPlugin(plugin);
                    queryResult.setRunning(false);
                    sess.save(queryResult);
                }

                if (queryResult.isRunning()) {
                    // query was previously running, update failed count
                    queryResult.incrementFailedCount();
                }

                queryResult.setStartTime(Calendar.getInstance(
                        TimeZone.getTimeZone("GMT")).getTime());
                queryResult.setRunning(true);
                sess.update(queryResult);
                return queryResult;
            }
        });
    }

    /**
     * Retrieves the plugins order by startTime.
     * 
     * @param latestStartTime
     * @param failedCount
     * @return
     */
    @SuppressWarnings("unchecked")
    public List<String> getPluginsByPurgeTime() {
        final String query = "select obj.id.plugin from " + daoClass.getName()
                + " obj order by obj.startTime asc, obj.plugin asc";
        return txTemplate.execute(new TransactionCallback<List<String>>() {
            @Override
            public List<String> doInTransaction(TransactionStatus status) {
                Query hibQuery = getCurrentSession().createQuery(query);
                List<String> result = hibQuery.list();
                return result;
            }
        });
    }

    /**
     * Updates a purge job status object
     * 
     * @param jobStatus
     *            The object to update
     */
    public void update(final PurgeJobStatus jobStatus) {
        txTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                getCurrentSession().update(jobStatus);
            }
        });
    }
}
