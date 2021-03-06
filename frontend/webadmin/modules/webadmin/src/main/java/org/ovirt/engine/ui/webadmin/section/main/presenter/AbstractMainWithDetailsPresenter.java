package org.ovirt.engine.ui.webadmin.section.main.presenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.ovirt.engine.core.compat.StringHelper;
import org.ovirt.engine.ui.common.place.PlaceRequestFactory;
import org.ovirt.engine.ui.common.presenter.ActionPanelPresenterWidget;
import org.ovirt.engine.ui.common.presenter.AddActionButtonEvent;
import org.ovirt.engine.ui.common.presenter.FragmentParams;
import org.ovirt.engine.ui.common.presenter.OvirtBreadCrumbsPresenterWidget;
import org.ovirt.engine.ui.common.presenter.PlaceTransitionHandler;
import org.ovirt.engine.ui.common.presenter.PluginActionButtonHandler;
import org.ovirt.engine.ui.common.uicommon.model.MainModelProvider;
import org.ovirt.engine.ui.common.widget.action.ActionButtonDefinition;
import org.ovirt.engine.ui.common.widget.table.ActionTable;
import org.ovirt.engine.ui.common.widget.table.HasActionTable;
import org.ovirt.engine.ui.uicommonweb.models.ApplySearchStringEvent;
import org.ovirt.engine.ui.uicommonweb.models.EntityModel;
import org.ovirt.engine.ui.uicommonweb.models.ListWithDetailsModel;
import org.ovirt.engine.ui.uicommonweb.models.SearchableListModel;
import org.ovirt.engine.ui.uicommonweb.models.tags.TagModel;
import org.ovirt.engine.ui.uicommonweb.place.WebAdminApplicationPlaces;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.event.logical.shared.ResizeHandler;
import com.google.gwt.event.shared.EventBus;
import com.google.gwt.event.shared.GwtEvent.Type;
import com.google.gwt.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.annotations.ContentSlot;
import com.gwtplatform.mvp.client.proxy.PlaceManager;
import com.gwtplatform.mvp.client.proxy.ProxyPlace;
import com.gwtplatform.mvp.client.proxy.RevealContentHandler;
import com.gwtplatform.mvp.shared.proxy.PlaceRequest;
import com.gwtplatform.mvp.shared.proxy.PlaceRequest.Builder;

/**
 * Base class for main tab presenters that work with {@link ListWithDetailsModel}.
 *
 * @param <T>
 *            Table row data type.
 * @param <M>
 *            Main model type.
 * @param <V>
 *            View type.
 * @param <P>
 *            Proxy type.
 */
public abstract class AbstractMainWithDetailsPresenter<T, M extends ListWithDetailsModel,
    V extends AbstractMainWithDetailsPresenter.ViewDef<T>, P extends ProxyPlace<?>>
        extends AbstractMainPresenter<T, M, V, P> implements PlaceTransitionHandler, DetailsTransitionHandler<T> {

    public interface ViewDef<T> extends View, HasActionTable<T> {
        void setDetailPlaceTransitionHandler(DetailsTransitionHandler<T> handler);

        void resizeToFullHeight();

        HandlerRegistration addWindowResizeHandler(ResizeHandler handler);

        void setPlaceTransitionHandler(PlaceTransitionHandler handler);
    }

    @ContentSlot
    public static final Type<RevealContentHandler<?>> TYPE_SetSearchPanel = new Type<>();

    @ContentSlot
    public static final Type<RevealContentHandler<?>> TYPE_SetBreadCrumbs = new Type<>();

    @ContentSlot
    public static final Type<RevealContentHandler<?>> TYPE_SetActionPanel = new Type<>();

    private final SearchPanelPresenterWidget<T, M> searchPanelPresenterWidget;

    private final OvirtBreadCrumbsPresenterWidget<T, M> breadCrumbsPresenterWidget;

    private PluginActionButtonHandler actionButtonPluginHandler;
    private boolean resizing = false;
    private String parameterName = null;
    private T switchToItem = null;

    @Inject
    private SearchStringCollector searchStringCollector;

    public AbstractMainWithDetailsPresenter(EventBus eventBus, V view, P proxy,
            PlaceManager placeManager, MainModelProvider<T, M> modelProvider,
            SearchPanelPresenterWidget<T, M> searchPanelPresenterWidget,
            OvirtBreadCrumbsPresenterWidget<T, M> breadCrumbsPresenterWidget,
            ActionPanelPresenterWidget<T, M> actionPanelPresenterWidget) {
        super(eventBus, view, proxy, placeManager, modelProvider, actionPanelPresenterWidget);
        this.searchPanelPresenterWidget = searchPanelPresenterWidget;
        this.breadCrumbsPresenterWidget = breadCrumbsPresenterWidget;
        this.breadCrumbsPresenterWidget.hideSelectedName();
    }

    protected ActionTable<T> getTable() {
        return getView().getTable();
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void onBind() {
        super.onBind();

        getView().setPlaceTransitionHandler(this);
        registerHandler(getTable().getSelectionModel()
                .addSelectionChangeHandler(event -> {
                    // Update main model selection
                    modelProvider.setSelectedItems(getSelectedItems());

                    // Let others know that the table selection has changed
                    fireTableSelectionChangeEvent();
               }));
        registerHandler(getEventBus().addHandler(ApplySearchStringEvent.getType(), event -> {
            applySearchString(event.getSearchString());
        }));
        getView().setDetailPlaceTransitionHandler(this);
        registerHandler(getView().addWindowResizeHandler(e -> {
            if (!resizing) {
                Scheduler.get().scheduleDeferred(() -> {
                    getView().resizeToFullHeight();
                    resizing = false;
                });
                resizing = true;
            }
        }));
        String searchString = searchStringCollector.getSearchStringPrefix(modelProvider.getModel().getSearchString());
        if (searchString != null) {
            // Someone set search string before we were instantiated, update the search string.
            applySearchString(searchString);
        }
        Scheduler.get().scheduleDeferred(() ->
            addPluginActionButtons(actionButtonPluginHandler.getButtons(getProxy().getNameToken())));
        registerHandler(getEventBus().addHandler(AddActionButtonEvent.getType(),
            event -> {
                if (getProxy().getNameToken().equals(event.getHistoryToken())) {
                    List<ActionButtonDefinition<?>> pluginActionButtonList = new ArrayList<>();
                    pluginActionButtonList.add(event.getButtonDefinition());
                    addPluginActionButtons(pluginActionButtonList);
                }
            }
        ));

        getModel().getItemsChangedEvent().addListener((ev, sender, args) -> {
            if (this.parameterName != null) {
                switchToName(parameterName);
                parameterName = null;
            }
        });
        getModel().getSelectedItemChangedEvent().addListener((ev, sender, args) -> {
            if (switchToItem != null) {
                // This needs to be deferred otherwise the main view will be shown second overriding this one.
                Scheduler.get().scheduleDeferred(() -> {
                    handlePlaceTransition(true);
                    switchToItem = null;
                });
            }
        });
        if (hasSearchPanelPresenterWidget()) {
            setInSlot(TYPE_SetSearchPanel, searchPanelPresenterWidget);
        }
        if (hasActionPanelPresenterWidget()) {
            setInSlot(TYPE_SetActionPanel, getActionPanelPresenterWidget());
        }
    }

    public void applySearchString(String searchString) {
        if (modelProvider.getModel() instanceof SearchableListModel) {
            @SuppressWarnings("unchecked")
            SearchableListModel<?, ? extends EntityModel<?>> listModel = modelProvider.getModel();
            if (StringHelper.isNotNullOrEmpty(searchString)
                    && searchString.startsWith(listModel.getDefaultSearchString())) {
                // search string for this model found.
                listModel.setSearchString(searchString);
                listModel.getSearchCommand().execute();
            }
        }
    }

    @Override
    public void handlePlaceTransition(boolean linkClicked) {
        if (hasSelection() && hasSelectionDetails() && linkClicked) {
            // Sub tab panel is shown upon revealing the sub tab, in order to avoid
            // the 'flicker' effect due to the panel still showing previous content
            placeManager.revealPlace(getSubTabRequest());
        } else {
            placeManager.revealPlace(getMainViewRequest());
        }
    }

    protected boolean hasSelectionDetails() {
        return true;
    }

    @Override
    protected void onReveal() {
        super.onReveal();

        setInSlot(TYPE_SetSearchPanel, searchPanelPresenterWidget);
        setInSlot(TYPE_SetBreadCrumbs, breadCrumbsPresenterWidget);
        if (hasActionPanelPresenterWidget()) {
            getTable().setActionMenus(getActionPanelPresenterWidget().getActionButtons());
        }
        breadCrumbsPresenterWidget.hideSelectedName();
        breadCrumbsPresenterWidget.rebuildBreadCrumbs();
        getView().resizeToFullHeight();
        PlaceRequest currentPlace = placeManager.getCurrentPlaceRequest();
        Set<FragmentParams> params = FragmentParams.getParams(currentPlace);
        params.forEach(param -> {
            switch(param) {
            case NAME:
                switchToName(currentPlace.getParameter(FragmentParams.NAME.getName(), ""));
                break;
            case SEARCH:
                String search = currentPlace.getParameter(FragmentParams.SEARCH.getName(), "");
                if (!"".equals(search)) {
                    // We have a search parameter. The tokenizer has already run it through URL decode so we should be
                    // able to simply pass it to setSearchString in the model.
                    applySearchString(getModel().getDefaultSearchString() + search);
                }
                break;
            default:
                break;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void switchToName(String name) {
        if (!"".equals(name)) {
            T namedItem = (T) FragmentParams.findItemByName(name, getModel());
            if (namedItem != null) {
                getModel().getSelectionModel().clear();
                getModel().getSelectionModel().setSelected(namedItem, true);
                // Simulate a click on the link.
                switchToItem = namedItem;
            } else if (getModel().getItems() != null) {
                // Couldn't find the named item, and the items have loaded, try to search for it by query.
                applySearchString(getModel().getDefaultSearchString() + "name=" + name); // $NON-NLS-1$
                Scheduler.get().scheduleDeferred(() -> parameterName = name);
            } else {
                // items haven't loaded yet, store the name for use when the items have loaded.
                parameterName = name;
            }
        }
    }

    @Override
    protected void onHide() {
        getTable().hideContextMenu();
        getView().resizeToFullHeight();
    }

    /**
     * Subclasses should fire an event to indicate that the table selection has changed.
     */
    protected abstract void fireTableSelectionChangeEvent();

    protected PlaceRequest getSubTabRequest() {
        String subTabName;
        modelProvider.getModel().ensureActiveDetailModel();
        subTabName = modelProvider.getModel().getActiveDetailModel().getHashName();
        String requestToken = getMainViewRequest().getNameToken() + WebAdminApplicationPlaces.SUB_TAB_PREFIX + subTabName;
        return PlaceRequestFactory.get(requestToken);
    }

    /**
     * Returns items currently selected in the table.
     */
    protected List<T> getSelectedItems() {
        return getTable().getSelectionModel().asMultiSelectionModel().getSelectedList();
    }

    /**
     * Returns {@code true} when there is at least one item selected in the table, {@code false} otherwise.
     */
    protected boolean hasSelection() {
        return !getSelectedItems().isEmpty();
    }

    /**
     * Deselects any items currently selected in the table.
     */
    protected void clearSelection() {
        getTable().getSelectionModel().clear();
    }

    public SearchPanelPresenterWidget<?, ?> getSearchPanelPresenterWidget() {
        return searchPanelPresenterWidget;
    }

    public OvirtBreadCrumbsPresenterWidget<T, M> getBreadCrumbs() {
        return breadCrumbsPresenterWidget;
    }

    public boolean hasSearchPanelPresenterWidget() {
        return getSearchPanelPresenterWidget() != null;
    }

    public boolean hasActionPanelPresenterWidget() {
        return getActionPanelPresenterWidget() != null;
    }

    protected void setTags(List<TagModel> tags) {
        searchPanelPresenterWidget.setTags(tags);
    }

    private void addPluginActionButtons(List<ActionButtonDefinition<?>> pluginActionButtonList) {
        if (hasActionPanelPresenterWidget()) {
            for(ActionButtonDefinition<?> buttonDef: pluginActionButtonList) {
                getActionPanelPresenterWidget().addActionButton((ActionButtonDefinition) buttonDef);
            }
        }
    }

    @Inject
    public void setActionButtonPluginHandler(PluginActionButtonHandler actionButtonPluginHandler) {
        this.actionButtonPluginHandler = actionButtonPluginHandler;
    }

    @Override
    public void handlePlaceTransition(String nameToken, Map<String, String> parameters) {
        final Builder builder = new Builder();
        builder.nameToken(nameToken);
        builder.with(parameters);
        placeManager.revealPlace(builder.build());
    }
}
