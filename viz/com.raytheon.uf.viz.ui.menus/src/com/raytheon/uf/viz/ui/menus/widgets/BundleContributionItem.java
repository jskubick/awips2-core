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
package com.raytheon.uf.viz.ui.menus.widgets;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IPerspectiveRegistry;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;

import com.raytheon.uf.common.localization.ILocalizationFile;
import com.raytheon.uf.common.localization.LocalizationFile;
import com.raytheon.uf.common.localization.PathManagerFactory;
import com.raytheon.uf.common.localization.exception.LocalizationException;
import com.raytheon.uf.common.menus.xml.CommonBundleMenuContribution;
import com.raytheon.uf.common.menus.xml.VariableSubstitution;
import com.raytheon.uf.common.status.IUFStatusHandler;
import com.raytheon.uf.common.status.UFStatus;
import com.raytheon.uf.common.status.UFStatus.Priority;
import com.raytheon.uf.common.time.BinOffset;
import com.raytheon.uf.common.time.DataTime;
import com.raytheon.uf.common.time.ISimulatedTimeChangeListener;
import com.raytheon.uf.common.time.SimulatedTime;
import com.raytheon.uf.common.util.VariableSubstitutor;
import com.raytheon.uf.viz.core.IDisplayPaneContainer;
import com.raytheon.uf.viz.core.VizApp;
import com.raytheon.uf.viz.core.exception.VizException;
import com.raytheon.uf.viz.core.jobs.JobPool;
import com.raytheon.uf.viz.core.procedures.Bundle;
import com.raytheon.uf.viz.core.procedures.BundleUtil;
import com.raytheon.uf.viz.core.procedures.BundleUtil.BundleDataItem;
import com.raytheon.uf.viz.core.procedures.Procedure;
import com.raytheon.uf.viz.core.rsc.URICatalog;
import com.raytheon.uf.viz.core.rsc.URICatalog.IURIRefreshCallback;
import com.raytheon.uf.viz.ui.menus.xml.BundleMenuContribution;
import com.raytheon.viz.ui.EditorUtil;
import com.raytheon.viz.ui.UiUtil;
import com.raytheon.viz.ui.actions.LoadBundleHandler;
import com.raytheon.viz.ui.actions.LoadPerspectiveHandler;

/**
 * Provides an Eclipse menu contribution that loads a bundle, and is decorated
 * with bundle availability times.
 * 
 * The dataURIs are utilized for the bundle availability times (this is
 * redundant in the bundle, but is necessary for performance reasons).
 * 
 * The bundle availability times are updated at two times:
 * <UL>
 * <LI>when the menu is pulled down, the times are checked to guarantee
 * consistency
 * <LI>while the menu is open, a callback is utilized to keep the menu up to
 * date
 * </UL>
 * 
 * 
 * <pre>
 * 
 * SOFTWARE HISTORY
 * 
 * Date          Ticket#  Engineer  Description
 * ------------- -------- --------- --------------------------------------------
 * Mar 12, 2009  2214     chammack  Initial creation
 * Jan 14, 2013  1442     rferrel   Add Simulated Time Change Listener.
 * Aug 30, 2013  2310     bsteffen  Move loading of bundle to LoadBundleHandler.
 * Mar 25, 2014  2857     mpduff    In the case of missing menu text throw
 *                                  exception stating the id of the missing
 *                                  text.
 * Jun 09, 2014  3266     njensen   Removed useless code
 * Jan 28, 2016  5294     bsteffen  Substitute when combining substitutions
 * Nov 08, 2016  5976     bsteffen  Use VariableSubstitutor directly
 * Dec 16, 2016  5976     bsteffen  Use localization file streams
 * 
 * </pre>
 * 
 * @author chammack
 */
public class BundleContributionItem extends ContributionItem {
    private static final transient IUFStatusHandler statusHandler = UFStatus
            .getHandler(BundleContributionItem.class);

    protected static JobPool prepareBundleJobPool = new JobPool(
            "Preparing menu entries", 4);

    /** The bundle menu item widget */
    protected MenuItem widget;

    /** Variable substitutions from the menu files */
    protected Map<String, String> substitutions;

    protected Listener menuItemListener;

    protected Set<BundleDataItem> pdoMapList;

    protected BundleMenuContribution menuContribution;

    protected static final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat(
            "dd.HHmm");

    protected String menuText;

    protected boolean shownBefore;

    protected final static String NOT_AVAILABLE = "--.----";

    protected final static String UNKNOWN = "??.????";

    protected DataTime lastUsedTime;

    protected boolean queryPerformed = false;

    protected boolean performQuery = true;

    /**
     * A flag to indicate simulated time has changed and item's values needs to
     * be refreshed the next time it is displayed.
     */
    private boolean timeChangeUpdate = false;

    public BundleContributionItem(CommonBundleMenuContribution contribution,
            VariableSubstitution[] includeSubstitutions) throws VizException {
        /*
         * Combine the substitutions and pass the result off to the private
         * constructor.
         */
        this(contribution,
                getSubstitutions(contribution, includeSubstitutions));
    }

    /**
     * Method for creating a combined substitution map during construction. This
     * cannot be inlined because
     * {@link VariableSubstitution#substituteAndCombine(VariableSubstitution[], VariableSubstitution[])}
     * throws a ParseException but the Constructor must throw a VizException
     */
    private static Map<String, String> getSubstitutions(
            CommonBundleMenuContribution contribution,
            VariableSubstitution[] includeSubstitutions) throws VizException {
        try {
            return VariableSubstitution
                    .toMap(VariableSubstitution.substituteAndCombine(
                            includeSubstitutions, contribution.substitutions));
        } catch (ParseException e) {
            throw new VizException(
                    "Error processing substitutions for menu item "
                            + contribution.id,
                    e);
        }
    }

    /**
     * This private constructor is necessary because the combined variable
     * substitution needs to be applied to the id before calling super and also
     * needs to be saved. The only way to do this is to combine the
     * substitutions before calling this constructor so that they are available
     * as a local variable for both the substitution in the id and for saving in
     * a field.
     * 
     * @param contribution
     *            the deserialized contribution information
     * @param substitutions
     *            The combination of substitutions that were passed into the
     *            constructor and the substitutions from the contribution.
     * @throws VizException
     */
    private BundleContributionItem(CommonBundleMenuContribution contribution,
            Map<String, String> substitutions) throws VizException {
        super(processVariables(contribution.id, substitutions));
        this.performQuery = contribution.timeQuery;
        this.menuContribution = new BundleMenuContribution();
        this.menuContribution.xml = contribution;
        // this.keySet = new HashSet<URIKey>();
        // this.adjustedKeyMap = new HashMap<URIKey, URIKey>();

        // Build the substitutions:
        // Everything defaults to the include value
        // Fill in contribution substitutions from include and possible override
        this.substitutions = substitutions;
        if (contribution.suppressErrors != null) {
            contribution.suppressErrors = String.valueOf(this.substitutions
                    .get(contribution.suppressErrors.substring(2,
                            contribution.suppressErrors.length() - 1)));
        }

        // Substitute the menu text
        if (menuContribution.xml.text == null) {
            throw new IllegalStateException("Missing menu text for menu id: "
                    + menuContribution.xml.id);
        }

        this.menuText = processVariables(menuContribution.xml.text,
                this.substitutions);

        // The bundle persists for the life of CAVE; no need to remove the
        // listener.
        ISimulatedTimeChangeListener stcl = new ISimulatedTimeChangeListener() {

            @Override
            public void timechanged() {
                // Updating the value here will generate a flood of requests for
                // all bundles.
                //
                // This will force the update of the item's value the next time
                // it is displayed.
                //
                // Any open widget using the bundle will need to handle the
                // update.
                timeChangeUpdate = true;
            }
        };
        SimulatedTime.getSystemTime().addSimulatedTimeChangeListener(stcl);
    }

    private static String processVariables(String string,
            Map<String, String> variables) throws VizException {
        try {
            return VariableSubstitutor.processVariables(string, variables);
        } catch (ParseException e) {
            throw new VizException("Error processing variable substitution", e);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.eclipse.jface.action.ContributionItem#fill(org.eclipse.swt.widgets
     * .Menu, int)
     */
    @Override
    public void fill(Menu parent, int index) {
        if (this.menuContribution == null) {
            return;
        }

        if (widget != null || parent == null) {
            return;
        }

        MenuItem item = null;
        if (index >= 0) {
            item = new MenuItem(parent, SWT.PUSH, index);
        } else {
            item = new MenuItem(parent, SWT.PUSH);
        }

        item.setData(this);

        item.addListener(SWT.Dispose, getItemListener());
        item.addListener(SWT.Selection, getItemListener());
        item.addListener(SWT.Activate, getItemListener());
        item.getParent().addListener(SWT.Show, getItemListener());

        widget = item;
        updateMenuText();
        update(null);
    }

    protected void updateMenuTextAsync() {
        VizApp.runAsync(new Runnable() {
            @Override
            public void run() {
                updateMenuText();
            }
        });
    }

    /**
     * This allows a widget such as an open dialog to force the bundle to query
     * for new values based on Simulated Time. Allows a widget to refresh its
     * display after a user has modified Simulated Time.
     */
    public void refreshText() {
        lastUsedTime = null;
        if (pdoMapList != null && pdoMapList.size() > 0) {
            URICatalog cat = URICatalog.getInstance();
            for (BundleDataItem d : pdoMapList) {
                cat.query(d.metadata);
            }
        }
        timeChangeUpdate = false;
    }

    protected synchronized void updateMenuText() {
        if (widget == null)
            return;

        if (!performQuery) {
            widget.setText(menuText);

            // notify things of menu update times
            Event event = new Event();
            event.data = widget;
            event.widget = widget;
            widget.notifyListeners(SWT.Modify, event);
            return;
        }

        String dateStr = UNKNOWN;

        boolean useReferenceTime = this.menuContribution.xml.useReferenceTime;

        if (lastUsedTime != null) {
            // We have a
            DATE_FORMATTER.setTimeZone(TimeZone.getTimeZone("UTC"));

            Date timeToUse;
            if (useReferenceTime) {
                timeToUse = lastUsedTime.getRefTime();
            } else {
                timeToUse = lastUsedTime.getValidTime().getTime();
            }

            dateStr = DATE_FORMATTER.format(timeToUse);
        } else if (this.queryPerformed) {
            // indicates that query has completed, and data is not there
            dateStr = NOT_AVAILABLE;
        }

        String labelStr = this.menuText + " \t" + dateStr;

        widget.setText(labelStr);

        // notify things of menu update times
        Event event = new Event();
        event.data = widget;
        event.widget = widget;
        widget.notifyListeners(SWT.Modify, event);
    }

    protected void updateTime(DataTime time, BinOffset offset) {
        BundleContributionItem.this.queryPerformed = true;

        if (time != null) {
            boolean useReferenceTime = BundleContributionItem.this.menuContribution.xml.useReferenceTime;

            if (offset != null) {
                time = offset.getNormalizedTime(time);
            }
            // compare refTime
            // set mostRecentProduct to key.dataTime if key.dataTime
            // is larger or mostRecentProduct is null
            if (BundleContributionItem.this.lastUsedTime == null) {
                BundleContributionItem.this.lastUsedTime = time.clone();

            } else {
                if (useReferenceTime) {
                    if (BundleContributionItem.this.lastUsedTime.getRefTime()
                            .compareTo(time.getRefTime()) < 0) {
                        BundleContributionItem.this.lastUsedTime = time.clone();
                    }
                } else {
                    if (BundleContributionItem.this.lastUsedTime
                            .compareTo(time) < 0) {
                        BundleContributionItem.this.lastUsedTime = time.clone();
                    }
                }
            }
        }

        updateMenuTextAsync();
    }

    private Listener getItemListener() {
        if (menuItemListener == null) {
            menuItemListener = new Listener() {
                @Override
                public void handleEvent(Event event) {
                    switch (event.type) {
                    case SWT.Dispose:
                        handleWidgetDispose(event);
                        break;
                    case SWT.Selection:
                        if (event.widget != null) {
                            loadBundle();
                        }
                        break;
                    case SWT.Show:
                        onShow();
                        break;
                    }
                }

            };
        }
        return menuItemListener;
    }

    /**
     * Called when the menu is about to be shown
     * 
     * First see if the item has ever been shown before. If not, prepare the
     * bundle (parse the metadata maps out)
     * 
     */
    protected void onShow() {
        if (timeChangeUpdate) {
            refreshText();
            return;
        }

        if (performQuery) {
            if (!shownBefore) {
                shownBefore = true;
                prepareBundleJobPool.schedule(new PrepareBundleJob());
            }
        }

        if (widget != null
                && (widget.getText() == null || widget.getText().equals(""))) {
            updateMenuText();
        }
    }

    private void handleWidgetDispose(Event event) {
        if (event.widget == widget) {
            widget.removeListener(SWT.Selection, getItemListener());
            widget.removeListener(SWT.Dispose, getItemListener());
            widget = null;
        }
    }	

    private void loadBundle() {
    	
        try {
        	
            boolean fullBundleLoad = false;
            
            if (this.menuContribution.xml.fullBundleLoad != null) {
                fullBundleLoad = this.menuContribution.xml.fullBundleLoad;
            }
            
            if (this.getId().equalsIgnoreCase("procedure")) {
            	/* 
            	 * Load as Procedure (list of Bundles)
            	 */
            	LocalizationFile file = PathManagerFactory.getPathManager()
                        .getStaticLocalizationFile(
                                this.menuContribution.xml.bundleFile);
            	Procedure p = (Procedure) LoadPerspectiveHandler
                		.deserialize(file.getFile());

                try {
                	String procStr = p.toXML();
	                String substStr = VariableSubstitutor.processVariables(procStr,
								substitutions);
	                Procedure pr = Procedure.loadProcedure(substStr);
	                
	                LoadPerspectiveHandler.loadProcedureToScreen(pr, true);
	                
				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (VizException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            } else {
            	/*
            	 * Load as Bundle
            	 */
	            new LoadBundleHandler(this.menuContribution.xml.bundleFile,
	                    substitutions, this.menuContribution.xml.editorType,
	                    fullBundleLoad).execute(null);
            }
            
            if (this.menuContribution.xml.command != null) {
                ICommandService service = (ICommandService) PlatformUI
                        .getWorkbench().getService(ICommandService.class);
                try {
                    Map<String, String> parms = new HashMap<String, String>();
                    if (substitutions != null) {
                        parms = substitutions;
                    }
                    Command command = service
                            .getCommand(this.menuContribution.xml.command);
                    command.executeWithChecks(
                            new ExecutionEvent(command, parms, null, null));
                } catch (Exception e) {
                    statusHandler
                            .handle(Priority.PROBLEM,
                                    "Failed to execute command: "
                                            + this.menuContribution.xml.command,
                                    e);
                }
            }

        } catch (ExecutionException e) {
            statusHandler.handle(Priority.PROBLEM, "Error loading bundle : "
                    + this.menuContribution.xml.bundleFile, e);
        }

    }

    private Set<BundleDataItem> loadBundleFromXml() {
        try {
            LocalizationFile file = PathManagerFactory.getPathManager()
                    .getStaticLocalizationFile(
                            this.menuContribution.xml.bundleFile);
            if (file == null){
            	statusHandler.error(this.menuContribution.xml.bundleFile + " not found");
            }
            
            Bundle b;
            
            /*
             * Account for procedure files loaded as a bundleContribution type,
             * can contain views & bundles. One bundle should be queried for metadata
             * TODO: query all and show latest? I think this is just for the menu item 
             * to fill out.
             */
            if (this.getId().equalsIgnoreCase("procedure")) {
                Procedure p = (Procedure) LoadPerspectiveHandler
                		.deserialize(file.getFile());
                Bundle[] bs = p.getBundles();
                b = bs[0];
            } 

            try (InputStream stream = file.openInputStream()) {
                b = Bundle.unmarshalBundle(stream, substitutions);
            }
            return BundleUtil.extractMetadata(b);
            
        } catch (VizException | LocalizationException | IOException e) {
            statusHandler.error(e.getLocalizedMessage(), e);
            return new HashSet<>();
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        if (widget != null) {
            widget.dispose();
            widget = null;
        }
    }

    private class BundleRefreshCallback implements IURIRefreshCallback {

        private final BinOffset offset;

        /**
         * @param optional2
         * @param offset
         */
        public BundleRefreshCallback(BinOffset offset) {
            this.offset = offset;
        }

        @Override
        public void updateTime(DataTime time) {
            BundleContributionItem.this.updateTime(time, offset);
        }
    }

    protected class PrepareBundleJob implements Runnable {

        @Override
        public void run() {
            BundleContributionItem.this.pdoMapList = loadBundleFromXml();
            for (BundleDataItem d : BundleContributionItem.this.pdoMapList) {
                URICatalog.getInstance().catalogAndQueryDataURI(d.metadata,
                        new BundleRefreshCallback(d.offset));
            }
        }

    }
}
