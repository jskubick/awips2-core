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
package com.raytheon.uf.common.dataaccess;

import java.util.Arrays;

import com.raytheon.uf.common.dataaccess.exception.DataFactoryNotFoundException;
import com.raytheon.uf.common.dataaccess.exception.IncompatibleRequestException;
import com.raytheon.uf.common.dataaccess.exception.InvalidIdentifiersException;
import com.raytheon.uf.common.dataaccess.exception.TimeAgnosticDataException;
import com.raytheon.uf.common.dataaccess.exception.UnsupportedOutputTypeException;
import com.raytheon.uf.common.dataaccess.geom.IGeometryData;
import com.raytheon.uf.common.dataaccess.grid.IGridData;
import com.raytheon.uf.common.dataaccess.impl.DefaultDataRequest;
import com.raytheon.uf.common.dataplugin.level.Level;
import com.raytheon.uf.common.time.BinOffset;
import com.raytheon.uf.common.time.DataTime;
import com.raytheon.uf.common.time.TimeRange;

/**
 * The Data Access Layer is the published API for getting data through the Data
 * Access Framework. Code from other components should go through these methods
 * to retrieve the data. All methods may potentially throw
 * UnsupportedOperationException or IllegalArgumentException dependent on how
 * much support has been provided per datatype.
 * 
 * The implementation of this class is a retrieval of the corresponding factory
 * and then delegating the processing to that factory.
 * 
 * <pre>
 * 
 * SOFTWARE HISTORY
 * 
 * Date          Ticket#  Engineer    Description
 * ------------- -------- ----------- --------------------------
 * Oct 24, 2012           njensen     Initial creation
 * Feb 14, 2013  1614     bsteffen    Refactor data access framework to use
 *                                    single request.
 * Mar 03, 2014  2673     bsteffen    Add ability to query only ref times.
 * Jul 14, 2014  3184     njensen     Added new methods
 * Jul 30, 2014  3184     njensen     Renamed valid identifiers to optional
 * Mar 04, 2015  4217     mapeters    Sort available times.
 * Apr 13, 2016  5379     tgurney     Add getIdentifierValues()
 * Jun 01, 2016  5587     tgurney     Add new method signatures for
 *                                    getRequiredIdentifiers() and
 *                                    getOptionalIdentifiers()
 * Jun 07, 2016  5587     tgurney     Deprecate old get*Identifiers() methods
 * Jun 21, 2016  2416     rjpeter     Made getFactory() protected
 * </pre>
 * 
 * @author njensen
 */

public class DataAccessLayer {

    private DataAccessLayer() {
        // static interface only
    }

    /**
     * Gets the times of available data to the request
     * 
     * @param request
     *            the request to find available times for
     * @return the available times that match the request
     * @throws TimeAgnosticDataException
     */
    public static DataTime[] getAvailableTimes(IDataRequest request) {
        return getAvailableTimes(request, false);
    }

    /**
     * Gets the times of available data to the request
     * 
     * @param request
     *            the request to find available times for
     * @param refTimeOnly
     *            true if only unique refTimes should be returned(without a
     *            forecastHr)
     * @return the available times that match the request
     * @throws TimeAgnosticDataException
     */
    public static DataTime[] getAvailableTimes(IDataRequest request,
            boolean refTimeOnly) {
        IDataFactory factory = getFactory(request);
        DataTime[] times = factory.getAvailableTimes(request, refTimeOnly);
        Arrays.sort(times);
        return times;
    }

    /**
     * Gets the times of available data to the request with a BinOffset applied
     * 
     * @param request
     *            the request to find available times for
     * @param binOffset
     *            the BinOffset to apply
     * @return the available times with the bin offset applied that match the
     *         request
     * @throws TimeAgnosticDataException
     */
    public static DataTime[] getAvailableTimes(IDataRequest request,
            BinOffset binOffset) {
        IDataFactory factory = getFactory(request);
        DataTime[] times = factory.getAvailableTimes(request, binOffset);
        Arrays.sort(times);
        return times;
    }

    /**
     * Gets the data that matches the request at the specified times. If data is
     * time agnostic then simply don't pass in any DataTimes, for example
     * DataAccessLayer.getGridData(R)
     * 
     * @param request
     *            the request to get data for
     * @param times
     *            the times to get data for
     * @return the data that matches the request and times
     * @throws UnsupportedOutputTypeException
     *             if the factory for this datatype cannot produce IGridData
     */
    public static IGridData[] getGridData(IDataRequest request,
            DataTime... times) throws UnsupportedOutputTypeException {
        IDataFactory factory = getFactory(request);
        return factory.getGridData(request, times);
    }

    /**
     * Gets the data that matches the request within the time range
     * 
     * @param request
     *            the request to get data for
     * @param timeRange
     *            the time range to get data for
     * @return the data that matches the request and time range
     * @throws UnsupportedOutputTypeException
     *             if the factory for this datatype cannot produce IGeometryData
     */
    public static IGeometryData[] getGeometryData(IDataRequest request,
            TimeRange timeRange) throws UnsupportedOutputTypeException {
        IDataFactory factory = getFactory(request);
        return factory.getGeometryData(request, timeRange);
    }

    /**
     * Gets the data that matches the request at the specified times. If data is
     * time agnostic then simply don't pass in any DataTimes, for example
     * DataAccessLayer.getGeometryData(R)
     * 
     * @param request
     *            the request to get data for
     * @param times
     *            the times to get data for
     * @return the data that matches the request and times
     * @throws UnsupportedOutputTypeException
     *             if the factory for this datatype cannot produce IGeometryData
     */
    public static IGeometryData[] getGeometryData(IDataRequest request,
            DataTime... times) throws UnsupportedOutputTypeException {
        IDataFactory factory = getFactory(request);
        return factory.getGeometryData(request, times);
    }

    /**
     * Gets the data that matches the request within the time range.
     * 
     * @param request
     *            the request to get data for
     * @param timeRange
     *            the time range to get data for
     * @return the data that matches the request and time range
     * @throws UnsupportedOutputTypeException
     *             if the factory for this datatype cannot produce IGridData
     */
    public static IGridData[] getGridData(IDataRequest request,
            TimeRange timeRange) throws UnsupportedOutputTypeException {
        IDataFactory factory = getFactory(request);
        return factory.getGridData(request, timeRange);
    }

    /**
     * Gets the available location names that match the request without actually
     * requesting the data.
     * 
     * @param request
     * @return the available location names if the data was requested
     * @throws IncompatibleRequestException
     *             if the factory for this datatype does not support location
     *             names
     */
    public static String[] getAvailableLocationNames(IDataRequest request) {
        IDataFactory factory = getFactory(request);
        return factory.getAvailableLocationNames(request);
    }

    /**
     * Shortcut to creating a new request.
     * 
     * @return an empty IDataRequest
     */
    public static IDataRequest newDataRequest() {
        return new DefaultDataRequest();
    }

    /**
     * Returns the factory that should process the request. Will never return
     * null; will instead throw exceptions.
     * 
     * @param request
     * @return the factory that matches the request
     * @throws DataFactoryNotFoundException
     */
    protected static IDataFactory getFactory(IDataRequest request) {
        return DataFactoryRegistry.getInstance().getFactory(request);
    }

    /**
     * Returns the names of the datatypes that currently supported at runtime by
     * the Data Access Framework
     * 
     * @return
     */
    public static String[] getSupportedDatatypes() {
        return DataFactoryRegistry.getInstance().getRegisteredDatatypes();
    }

    /**
     * Gets the available parameter names that match the request
     * 
     * @param request
     *            the request to find matching parameter names for
     * @return the available parameter names that match the request
     */
    public static String[] getAvailableParameters(IDataRequest request) {
        return DataFactoryRegistry.getInstance().getFactory(request)
                .getAvailableParameters(request);
    }

    /**
     * Gets the available levels that match the request
     * 
     * @param request
     *            the request to find matching levels for
     * @return the available levels that match the request
     * @throws IncompatibleRequestException
     *             if the factory for this datatype does not support levels
     */
    public static Level[] getAvailableLevels(IDataRequest request) {
        return DataFactoryRegistry.getInstance().getFactory(request)
                .getAvailableLevels(request);
    }

    /**
     * Gets the identifiers that must be put on an IDataRequest for the
     * specified datatype
     * 
     * @param datatype
     *            the datatype to find required identifiers for requests
     * @return the identifiers that are required for this datatype's requests
     * @deprecated use getRequiredIdentifiers(IDataRequest) instead
     */
    @Deprecated
    public static String[] getRequiredIdentifiers(String datatype) {
        IDataRequest request = newDataRequest();
        request.setDatatype(datatype);
        return getRequiredIdentifiers(request);
    }

    /**
     * Gets the identifiers that must be put on the specified IDataRequest
     * 
     * @param request
     *            The request to get required identifiers for
     * @return the identifiers that are required for this datatype's requests
     */
    public static String[] getRequiredIdentifiers(IDataRequest request) {
        return DataFactoryRegistry.getInstance().getFactory(request)
                .getRequiredIdentifiers(request);
    }

    /**
     * Gets the optional identifiers that will be recognized by requests for the
     * particular datatype.
     * 
     * @param datatype
     *            the datatype to find all possible identifiers for
     * @return the identifiers that are recognized by requests for this datatype
     * @deprecated use getOptionalIdentifiers(IDataRequest) instead
     */
    @Deprecated
    public static String[] getOptionalIdentifiers(String datatype) {
        IDataRequest request = newDataRequest();
        request.setDatatype(datatype);
        return getOptionalIdentifiers(request);
    }

    /**
     * Gets the optional identifiers that are allowed on the specified
     * IDataRequest
     * 
     * @param request
     *            the request to find optional identifiers for
     * @return the optional identifiers that are allowed for this request
     */
    public static String[] getOptionalIdentifiers(IDataRequest request) {
        return DataFactoryRegistry.getInstance().getFactory(request)
                .getOptionalIdentifiers(request);
    }

    /**
     * Gets the allowed values for a particular identifier recognized by a
     * particular datatype
     * 
     * @param request
     *            the request to find identifier values for
     * @param identifierKey
     *            the identifier to find all allowed values for
     * @return the allowed values for the specified identifier recognized by
     *         this datatype
     * @throws InvalidIdentifiersException
     *             if the specified identifier is not a recognized identifier
     *             for this datatype
     */
    public static String[] getIdentifierValues(IDataRequest request,
            String identifierKey) {
        return DataFactoryRegistry.getInstance().getFactory(request)
                .getIdentifierValues(request, identifierKey);
    }
}
