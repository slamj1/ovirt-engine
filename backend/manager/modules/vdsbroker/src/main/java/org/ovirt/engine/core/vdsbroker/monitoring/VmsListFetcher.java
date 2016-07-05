package org.ovirt.engine.core.vdsbroker.monitoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VmDynamic;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.common.vdscommands.GetVmStatsVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.common.vdscommands.VdsIdAndVdsVDSCommandParametersBase;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.DbFacade;
import org.ovirt.engine.core.dao.VmDao;
import org.ovirt.engine.core.vdsbroker.ResourceManager;
import org.ovirt.engine.core.vdsbroker.VdsManager;
import org.ovirt.engine.core.vdsbroker.vdsbroker.entities.VdsmVm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * in-charge of fetching vms list together with the db counter-part
 * and store for analysis on VdsManager
 */
public class VmsListFetcher {

    protected VdsManager vdsManager;
    protected List<Pair<VM, VdsmVm>> changedVms;
    protected Map<Guid, VdsmVm> vdsmVms;
    private Map<Guid, VM> dbVms;

    // dependencies
    private DbFacade dbFacade;
    private ResourceManager resourceManager;

    private static final Logger log = LoggerFactory.getLogger(VmsListFetcher.class);

    public VmsListFetcher(VdsManager vdsManager) {
        this.vdsManager = vdsManager;
        this.dbFacade = DbFacade.getInstance();
        this.resourceManager = ResourceManager.getInstance();
    }

    public VmsListFetcher(VdsManager vdsManager, DbFacade dbFacade, ResourceManager resourceManager) {
        this.vdsManager = vdsManager;
        this.dbFacade = dbFacade;
        this.resourceManager = resourceManager;
    }

    @SuppressWarnings("unchecked")
    public boolean fetch() {
        VDSReturnValue pollReturnValue = poll();
        if (pollReturnValue.getSucceeded()) {
            vdsmVms = (Map<Guid, VdsmVm>) pollReturnValue.getReturnValue();
            onFetchVms();
            return true;
        } else {
            onError();
            return false;
        }
    }

    protected VDSReturnValue poll() {
        return getResourceManager().runVdsCommand(
                VDSCommandType.List,
                new VdsIdAndVdsVDSCommandParametersBase(vdsManager.getCopyVds()));
    }

    protected void onFetchVms() {
        dbVms = getVmDao().getAllRunningByVds(vdsManager.getVdsId());
        changedVms = new ArrayList<>();
        filterVms();
        gatherNonRunningVms(dbVms);
        saveLastVmsList(vdsmVms);
    }

    private void saveLastVmsList(Map<Guid, VdsmVm> vdsmVms) {
        ArrayList<VM> vms = new ArrayList<>(vdsmVms.size());
        for (VdsmVm vmInternalData : this.vdsmVms.values()) {
            if (dbVms.containsKey(vmInternalData.getVmDynamic().getId())) {
                vms.add(new VM(
                        dbVms.get(vmInternalData.getVmDynamic().getId()).getStaticData(),
                        vmInternalData.getVmDynamic(),
                        vmInternalData.getVmStatistics()));
            }
        }
        vdsManager.setLastVmsList(vms);
    }

    protected void onError() {
        dbVms = Collections.emptyMap();
        vdsmVms = Collections.emptyMap();
    }

    protected void filterVms() {
        for (VdsmVm vdsmVm : vdsmVms.values()) {
            VM dbVm = dbVms.get(vdsmVm.getVmDynamic().getId());

            gatherChangedVms(dbVm, vdsmVm);
        }
    }

    protected void gatherChangedVms(VM dbVm, VdsmVm vdsmVm) {
        if (statusChanged(dbVm, vdsmVm.getVmDynamic())) {
            VDSReturnValue vmStats =
                    getResourceManager().runVdsCommand(
                            VDSCommandType.GetVmStats,
                            new GetVmStatsVDSCommandParameters(vdsManager.getCopyVds(), vdsmVm.getVmDynamic().getId()));
            if (vmStats.getSucceeded()) {
                changedVms.add(new Pair<>(dbVm, (VdsmVm) vmStats.getReturnValue()));
            } else {
                if (dbVm != null) {
                    log.error(
                            "failed to fetch VM '{}' stats. status remain unchanged ({})",
                            dbVm.getName(),
                            dbVm.getStatus());
                }
            }
        }
    }

    private void gatherNonRunningVms(Map<Guid, VM> dbVms) {
        for (VM dbVm : dbVms.values()) {
            if (!vdsmVms.containsKey(dbVm.getId())) {
                // non running vms are also treated as changed VMs
                changedVms.add(new Pair<>(dbVm, null));
            }
        }
    }

    private boolean statusChanged(VM dbVm, VmDynamic vdsmVm) {
        return dbVm == null || (dbVm.getStatus() != vdsmVm.getStatus());
    }

    public VmDao getVmDao() {
        return dbFacade.getVmDao();
    }

    public ResourceManager getResourceManager() {
        return resourceManager;
    }

    public Collection<VdsmVm> getVdsmVms() {
        return vdsmVms.values();
    }

    public List<Pair<VM, VdsmVm>> getChangedVms() {
        return changedVms;
    }

}
