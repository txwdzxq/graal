/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.visualizer.filterwindow;

import static jdk.graal.compiler.graphio.parsing.model.KnownPropertyNames.PROPNAME_CLASS;
import static jdk.graal.compiler.graphio.parsing.model.KnownPropertyNames.PROPNAME_NAME;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import jdk.graal.compiler.graphio.parsing.Builder;
import jdk.graal.compiler.graphio.parsing.model.GraphContainer;
import jdk.graal.compiler.graphio.parsing.model.InputGraph;
import jdk.graal.compiler.graphio.parsing.model.InputNode;
import jdk.graal.compiler.graphio.parsing.model.Property;
import jdk.graal.compiler.graphio.parsing.model.Properties.RegexpPropertyMatcher;
import org.graalvm.visualizer.data.services.GraphViewer;
import org.graalvm.visualizer.data.services.InputGraphProvider;
import org.graalvm.visualizer.graph.Diagram;
import org.graalvm.visualizer.graph.Figure;
import org.graalvm.visualizer.search.Criteria;
import org.graalvm.visualizer.search.GraphSearchEngine;
import org.graalvm.visualizer.search.SimpleNodeProvider;
import org.graalvm.visualizer.search.ui.SearchResultsView;
import org.graalvm.visualizer.view.api.DiagramViewer;
import org.graalvm.visualizer.view.api.DiagramViewerEvent;
import org.graalvm.visualizer.view.api.DiagramViewerListener;
import org.netbeans.api.settings.ConvertAsProperties;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.util.Lookup;
import org.openide.util.NbBundle.Messages;
import org.openide.util.WeakListeners;
import org.openide.windows.TopComponent;

@ConvertAsProperties(
        dtd = "-//org.graalvm.visualizer.filterwindow//GraphSummary//EN",
        autostore = false
)
@TopComponent.Description(
        preferredID = GraphSummaryTopComponent.PREFERRED_ID,
        persistenceType = TopComponent.PERSISTENCE_ALWAYS
)
@TopComponent.Registration(mode = "customRightTopMode", openAtStartup = true, position = 200)
@ActionID(category = "Window", id = "org.graalvm.visualizer.filterwindow.GraphSummaryTopComponent")
@ActionReference(path = "Menu/Window", position = 46)
@TopComponent.OpenActionRegistration(
        displayName = "#CTL_GraphSummaryAction",
        preferredID = GraphSummaryTopComponent.PREFERRED_ID
)
@Messages({
        "CTL_GraphSummaryAction=Graph Summary",
        "CTL_GraphSummaryTopComponent=Summary",
        "HINT_GraphSummaryTopComponent=Shows node types and counts for the active graph.",
        "LBL_NoActiveGraph=No active graph",
        "LBL_CurrentGraph=Graph: {0}",
        "LBL_SummaryCounts={0} nodes in {1} types",
        "LBL_UnknownNodeType=<unknown>",
        "COL_NodeType=Node Type",
        "COL_NodeCount=Count",
        "DISPLAY_CombinedSearchValuesInProperty={0} in {1}"
})
public final class GraphSummaryTopComponent extends TopComponent {
    private static final String ACTION_OPEN_NODE_SEARCH = "graph-summary-open-node-search";

    static final String PREFERRED_ID = "GraphSummaryTopComponent";

    private final JLabel graphNameLabel = new JLabel();
    private final JLabel totalsLabel = new JLabel();
    private final NodeTypeSummaryTableModel tableModel = new NodeTypeSummaryTableModel();
    private final JTable table = new JTable(tableModel);

    private ActiveGraphSynchronizer synchronizer;
    private InputGraph currentGraph;
    private GraphContainer currentContainer;

    public GraphSummaryTopComponent() {
        setName(Bundle.CTL_GraphSummaryTopComponent());
        setToolTipText(Bundle.HINT_GraphSummaryTopComponent());
        initComponents();
        ensureSynchronizer();
        updateSummary(null, null, null);
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        JPanel header = new JPanel();
        header.setLayout(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel labels = new JPanel();
        labels.setLayout(new javax.swing.BoxLayout(labels, javax.swing.BoxLayout.Y_AXIS));
        labels.add(graphNameLabel);
        labels.add(totalsLabel);
        header.add(labels, BorderLayout.CENTER);

        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setColumnSelectionAllowed(false);
        table.setRowSelectionAllowed(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Color.class, new ColorSwatchRenderer());
        table.getColumnModel().getColumn(0).setMinWidth(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(22);
        table.getColumnModel().getColumn(0).setMaxWidth(22);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setMaxWidth(120);
        installTableActions();

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    @Override
    public void componentOpened() {
        ensureSynchronizer();
        if (synchronizer != null) {
            synchronizer.refresh();
        }
    }

    private void ensureSynchronizer() {
        if (synchronizer != null) {
            return;
        }
        GraphViewer viewerService = Lookup.getDefault().lookup(GraphViewer.class);
        if (viewerService != null) {
            synchronizer = new ActiveGraphSynchronizer(viewerService);
        }
    }

    private void updateSummary(DiagramViewer viewer, InputGraph graph, GraphContainer container) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateSummary(viewer, graph, container));
            return;
        }

        InputGraph previousGraph = currentGraph;
        Set<String> selectedNodeTypes = graph != null && graph == previousGraph ? selectedNodeTypes() : Set.of();

        currentGraph = graph;
        currentContainer = container;

        if (graph == null) {
            setGraphNameText(Bundle.LBL_NoActiveGraph());
            totalsLabel.setText(" ");
            tableModel.setRows(List.of());
            table.clearSelection();
            return;
        }

        Map<String, Integer> counts = new HashMap<>();
        Map<String, Map<Color, Integer>> colorCounts = new HashMap<>();
        Map<String, NodeSearchSpec> searchSpecs = new HashMap<>();
        Set<String> ambiguousSearchTypes = new HashSet<>();
        Diagram diagram = viewer == null ? null : viewer.getModel().getDiagramToView();

        if (diagram == null) {
            for (InputNode node : graph.getNodes()) {
                NodeTypeInfo info = nodeTypeInfo(node);
                counts.merge(info.nodeType(), 1, Integer::sum);
                rememberSearchSpec(searchSpecs, ambiguousSearchTypes, info);
            }
        } else {
            diagram.render(() -> {
                for (InputNode node : graph.getNodes()) {
                    NodeTypeInfo info = nodeTypeInfo(node);
                    String type = info.nodeType();
                    counts.merge(type, 1, Integer::sum);
                    rememberSearchSpec(searchSpecs, ambiguousSearchTypes, info);
                    Color color = resolveNodeColor(diagram, node);
                    if (color != null) {
                        colorCounts.computeIfAbsent(type, t -> new HashMap<>()).merge(color, 1, Integer::sum);
                    }
                }
            });
        }

        List<NodeTypeRow> rows = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            rows.add(new NodeTypeRow(entry.getKey(), entry.getValue(), dominantColor(colorCounts.get(entry.getKey())), searchSpecs.get(entry.getKey())));
        }
        rows.sort(Comparator
                .comparingInt(NodeTypeRow::count).reversed()
                .thenComparing(NodeTypeRow::nodeType, String.CASE_INSENSITIVE_ORDER));

        setGraphNameText(Bundle.LBL_CurrentGraph(graph.getName()));
        totalsLabel.setText(Bundle.LBL_SummaryCounts(graph.getNodeCount(), rows.size()));
        tableModel.setRows(rows);
        restoreSelectedNodeTypes(selectedNodeTypes);
    }

    private Set<String> selectedNodeTypes() {
        Set<String> selected = new HashSet<>();
        for (int viewRow : table.getSelectedRows()) {
            NodeTypeRow row = tableModel.getRow(table.convertRowIndexToModel(viewRow));
            if (row != null) {
                selected.add(row.nodeType());
            }
        }
        return selected;
    }

    private void restoreSelectedNodeTypes(Set<String> selectedNodeTypes) {
        table.clearSelection();
        if (selectedNodeTypes.isEmpty()) {
            return;
        }
        ListSelectionModel selectionModel = table.getSelectionModel();
        selectionModel.setValueIsAdjusting(true);
        try {
            for (int modelRow = 0; modelRow < tableModel.getRowCount(); modelRow++) {
                NodeTypeRow row = tableModel.getRow(modelRow);
                if (row != null && selectedNodeTypes.contains(row.nodeType())) {
                    int viewRow = table.convertRowIndexToView(modelRow);
                    if (viewRow >= 0) {
                        selectionModel.addSelectionInterval(viewRow, viewRow);
                    }
                }
            }
        } finally {
            selectionModel.setValueIsAdjusting(false);
        }
    }

    private void setGraphNameText(String text) {
        graphNameLabel.setText(text);
        graphNameLabel.setToolTipText(text);
        Dimension preferredSize = graphNameLabel.getUI().getPreferredSize(graphNameLabel);
        graphNameLabel.setMinimumSize(new Dimension(0, preferredSize.height));
        graphNameLabel.setPreferredSize(new Dimension(0, preferredSize.height));
        graphNameLabel.setMaximumSize(new Dimension(Short.MAX_VALUE, preferredSize.height));
    }

    private Color resolveNodeColor(Diagram diagram, InputNode node) {
        Figure figure = diagram.getFigure(node.getId()).orElse(null);
        return figure == null ? null : figure.getColor();
    }

    private Color dominantColor(Map<Color, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return null;
        }
        Color dominant = null;
        int dominantCount = Integer.MIN_VALUE;
        for (Map.Entry<Color, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > dominantCount) {
                dominant = entry.getKey();
                dominantCount = entry.getValue();
            }
        }
        return dominant;
    }

    private NodeTypeInfo nodeTypeInfo(InputNode node) {
        String type = node.getProperties().getString(PROPNAME_CLASS, null);
        if (type != null && !type.isBlank()) {
            return new NodeTypeInfo(type, new NodeSearchSpec(PROPNAME_CLASS, type));
        }
        Builder.NodeClass nodeClass = node.getNodeClass();
        if (nodeClass != null && nodeClass.className != null && !nodeClass.className.isBlank()) {
            return new NodeTypeInfo(nodeClass.className, new NodeSearchSpec(PROPNAME_CLASS, nodeClass.className));
        }
        String name = node.getProperties().getString(PROPNAME_NAME, null);
        if (name != null && !name.isBlank()) {
            return new NodeTypeInfo(name, new NodeSearchSpec(PROPNAME_NAME, name));
        }
        return new NodeTypeInfo(Bundle.LBL_UnknownNodeType(), null);
    }

    private void rememberSearchSpec(Map<String, NodeSearchSpec> searchSpecs, Set<String> ambiguousSearchTypes, NodeTypeInfo info) {
        NodeSearchSpec candidate = info.searchSpec();
        if (candidate == null || ambiguousSearchTypes.contains(info.nodeType())) {
            return;
        }
        NodeSearchSpec existing = searchSpecs.get(info.nodeType());
        if (existing == null) {
            searchSpecs.put(info.nodeType(), candidate);
        } else if (!existing.matches(candidate)) {
            searchSpecs.remove(info.nodeType());
            ambiguousSearchTypes.add(info.nodeType());
        }
    }

    private void installTableActions() {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 2) {
                    return;
                }
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewRow < 0) {
                    return;
                }
                if (!table.isRowSelected(viewRow)) {
                    table.setRowSelectionInterval(viewRow, viewRow);
                }
                if (table.getSelectedRowCount() > 1 && table.isRowSelected(viewRow)) {
                    openNodeSearchForSelectedRows();
                } else {
                    openNodeSearchForRow(tableModel.getRow(table.convertRowIndexToModel(viewRow)));
                }
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_OPEN_NODE_SEARCH);
        table.getActionMap().put(ACTION_OPEN_NODE_SEARCH, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openNodeSearchForSelectedRows();
            }
        });
    }

    private void openNodeSearchForSelectedRows() {
        openNodeSearch(selectedRows());
    }

    private List<NodeTypeRow> selectedRows() {
        List<NodeTypeRow> rows = new ArrayList<>();
        for (int viewRow : table.getSelectedRows()) {
            NodeTypeRow row = tableModel.getRow(table.convertRowIndexToModel(viewRow));
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private void openNodeSearchForRow(NodeTypeRow row) {
        openNodeSearch(row == null ? List.of() : List.of(row));
    }

    private void openNodeSearch(List<NodeTypeRow> rows) {
        if (currentGraph == null || currentContainer == null) {
            return;
        }
        Criteria criteria = createSearchCriteria(rows);
        if (criteria == null) {
            return;
        }
        GraphSearchEngine engine = new GraphSearchEngine(currentContainer, currentGraph, new SimpleNodeProvider());
        SearchResultsView.addSearchResults(engine);
        engine.newSearch(criteria, false);
    }

    private Criteria createSearchCriteria(List<NodeTypeRow> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        List<NodeSearchSpec> specs = new ArrayList<>(rows.size());
        Set<String> seenSpecs = new HashSet<>();
        for (NodeTypeRow row : rows) {
            if (row == null || !row.isSearchable()) {
                return null;
            }
            NodeSearchSpec spec = row.searchSpec();
            String specKey = spec.propertyName() + '\u0000' + spec.propertyValue();
            if (seenSpecs.add(specKey)) {
                specs.add(spec);
            }
        }
        if (specs.isEmpty()) {
            return null;
        }
        if (specs.size() == 1) {
            NodeSearchSpec spec = specs.get(0);
            RegexpPropertyMatcher matcher = new RegexpPropertyMatcher(spec.propertyName(), Pattern.quote(spec.propertyValue()), true, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
            return new Criteria().setMatcher(matcher);
        }
        return new CombinedNodeSearchCriteria(specs);
    }

    void writeProperties(Properties properties) {
        properties.setProperty("version", "1.0");
    }

    void readProperties(Properties properties) {
        properties.getProperty("version");
    }

    private final class ActiveGraphSynchronizer implements ChangeListener, DiagramViewerListener {
        private final GraphViewer viewerService;
        private DiagramViewer viewer;
        private DiagramViewerListener viewerListener;

        ActiveGraphSynchronizer(GraphViewer viewerService) {
            this.viewerService = viewerService;
            viewerService.addChangeListener(WeakListeners.change(this, viewerService));
        }

        void refresh() {
            updateFromActiveViewer();
        }

        @Override
        public void stateChanged(ChangeEvent e) {
            updateFromActiveViewer();
        }

        private void updateFromActiveViewer() {
            InputGraphProvider provider = viewerService.getActiveViewer();
            DiagramViewer activeViewer = null;
            InputGraph graph = null;
            if (provider != null) {
                graph = provider.getGraph();
                if (provider instanceof DiagramViewer) {
                    activeViewer = (DiagramViewer) provider;
                } else {
                    activeViewer = provider.getLookup().lookup(DiagramViewer.class);
                }
            }
            syncViewer(activeViewer);
            updateSummary(activeViewer, graph != null ? graph : viewerService.getActiveGraph(), provider != null ? provider.getContainer() : null);
        }

        private void syncViewer(DiagramViewer activeViewer) {
            if (viewer == activeViewer) {
                return;
            }
            if (viewer != null && viewerListener != null) {
                viewer.removeDiagramViewerListener(viewerListener);
            }
            viewer = activeViewer;
            if (activeViewer != null) {
                viewerListener = WeakListeners.create(DiagramViewerListener.class, this, activeViewer);
                activeViewer.addDiagramViewerListener(viewerListener);
            } else {
                viewerListener = null;
            }
        }

        @Override
        public void stateChanged(DiagramViewerEvent ev) {
        }

        @Override
        public void interactionChanged(DiagramViewerEvent ev) {
        }

        @Override
        public void displayChanged(DiagramViewerEvent ev) {
        }

        @Override
        public void diagramChanged(DiagramViewerEvent ev) {
            updateSummary(viewer, ev.getModel().getGraphToView(), ev.getModel().getContainer());
        }

        @Override
        public void diagramReady(DiagramViewerEvent ev) {
            updateSummary(viewer, ev.getModel().getGraphToView(), ev.getModel().getContainer());
        }
    }

    private static final class NodeTypeSummaryTableModel extends AbstractTableModel {
        private List<NodeTypeRow> rows = List.of();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "";
                case 1 -> Bundle.COL_NodeType();
                case 2 -> Bundle.COL_NodeCount();
                default -> super.getColumnName(column);
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0 -> Color.class;
                case 2 -> Integer.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            NodeTypeRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.color();
                case 2 -> row.count();
                default -> row.nodeType();
            };
        }

        NodeTypeRow getRow(int rowIndex) {
            if (rowIndex < 0 || rowIndex >= rows.size()) {
                return null;
            }
            return rows.get(rowIndex);
        }

        void setRows(List<NodeTypeRow> newRows) {
            rows = List.copyOf(newRows);
            fireTableDataChanged();
        }
    }

    private static final class NodeTypeInfo {
        private final String nodeType;
        private final NodeSearchSpec searchSpec;

        NodeTypeInfo(String nodeType, NodeSearchSpec searchSpec) {
            this.nodeType = nodeType;
            this.searchSpec = searchSpec;
        }

        String nodeType() {
            return nodeType;
        }

        NodeSearchSpec searchSpec() {
            return searchSpec;
        }
    }

    private static final class NodeSearchSpec {
        private final String propertyName;
        private final String propertyValue;

        NodeSearchSpec(String propertyName, String propertyValue) {
            this.propertyName = propertyName;
            this.propertyValue = propertyValue;
        }

        boolean matches(NodeSearchSpec other) {
            return other != null && propertyName.equals(other.propertyName) && propertyValue.equals(other.propertyValue);
        }

        String propertyName() {
            return propertyName;
        }

        String propertyValue() {
            return propertyValue;
        }
    }

    private static final class NodeTypeRow {
        private final String nodeType;
        private final int count;
        private final Color color;
        private final NodeSearchSpec searchSpec;

        NodeTypeRow(String nodeType, int count, Color color, NodeSearchSpec searchSpec) {
            this.nodeType = nodeType;
            this.count = count;
            this.color = color;
            this.searchSpec = searchSpec;
        }

        String nodeType() {
            return nodeType;
        }

        int count() {
            return count;
        }

        Color color() {
            return color;
        }

        boolean isSearchable() {
            return searchSpec != null;
        }

        String searchProperty() {
            return searchSpec.propertyName();
        }

        String searchValue() {
            return searchSpec.propertyValue();
        }

        NodeSearchSpec searchSpec() {
            return searchSpec;
        }
    }

    private static final class CombinedNodeSearchCriteria extends Criteria {
        private final List<NodeSearchSpec> specs;

        CombinedNodeSearchCriteria(List<NodeSearchSpec> specs) {
            this.specs = List.copyOf(specs);
            setMatcher(new CombinedNodeSearchMatcher(this.specs));
        }

        @Override
        public String toQueryString() {
            return joinSpecs(" OR ", true);
        }

        @Override
        public String toDisplayString(boolean allowHtml) {
            if (specs.isEmpty()) {
                return super.toDisplayString(allowHtml);
            }
            String property = specs.get(0).propertyName();
            boolean sameProperty = true;
            for (int i = 1; i < specs.size(); i++) {
                if (!property.equals(specs.get(i).propertyName())) {
                    sameProperty = false;
                    break;
                }
            }
            if (sameProperty) {
                return Bundle.DISPLAY_CombinedSearchValuesInProperty(joinValues(", "), property);
            }
            return joinSpecs("; ", false);
        }

        private String joinValues(String separator) {
            StringBuilder sb = new StringBuilder();
            for (NodeSearchSpec spec : specs) {
                if (sb.length() > 0) {
                    sb.append(separator);
                }
                sb.append(spec.propertyValue());
            }
            return sb.toString();
        }

        private String joinSpecs(String separator, boolean queryFormat) {
            StringBuilder sb = new StringBuilder();
            for (NodeSearchSpec spec : specs) {
                if (sb.length() > 0) {
                    sb.append(separator);
                }
                if (queryFormat) {
                    sb.append(spec.propertyName()).append('=').append(spec.propertyValue());
                } else {
                    sb.append(spec.propertyValue()).append(" in ").append(spec.propertyName());
                }
            }
            return sb.toString();
        }
    }

    private static final class CombinedNodeSearchMatcher implements jdk.graal.compiler.graphio.parsing.model.Properties.PropertyMatcher {
        private final List<RegexpPropertyMatcher> matchers;

        CombinedNodeSearchMatcher(List<NodeSearchSpec> specs) {
            matchers = new ArrayList<>(specs.size());
            for (NodeSearchSpec spec : specs) {
                matchers.add(new RegexpPropertyMatcher(spec.propertyName(), Pattern.quote(spec.propertyValue()), true, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));
            }
        }

        @Override
        public String getName() {
            return matchers.isEmpty() ? "" : matchers.get(0).getName();
        }

        @Override
        public boolean match(Object value) {
            for (RegexpPropertyMatcher matcher : matchers) {
                if (matcher.match(value)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Property<?> matchProperties(jdk.graal.compiler.graphio.parsing.model.Properties properties) {
            for (RegexpPropertyMatcher matcher : matchers) {
                Property<?> matched = matcher.matchProperties(properties);
                if (matched != null) {
                    return matched;
                }
            }
            return null;
        }
    }

    private static final class ColorSwatchRenderer extends DefaultTableCellRenderer {
        private static final Icon MISSING_SWATCH_ICON = new MissingSwatchIcon();

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            setText("");
            setIcon(value instanceof Color ? new SwatchIcon((Color) value) : MISSING_SWATCH_ICON);
            setOpaque(true);
            setBackground(table.getBackground());
            return this;
        }
    }

    private static final class SwatchIcon implements Icon {
        private final Color color;

        SwatchIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            paintSwatch(g, x, y, color, Color.DARK_GRAY, null);
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }
    }

    private static final class MissingSwatchIcon implements Icon {
        private static final Color FILL = new Color(0xD0, 0xD0, 0xD0);
        private static final Color BORDER = new Color(0xA8, 0xA8, 0xA8);
        private static final Color STRIKE = new Color(0x45, 0x45, 0x45);

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            paintSwatch(g, x, y, FILL, BORDER, STRIKE);
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }
    }

    private static void paintSwatch(Graphics g, int x, int y, Color fill, Color border, Color strike) {
        Color old = g.getColor();
        g.setColor(fill);
        g.fillRect(x + 1, y + 1, 11, 11);
        g.setColor(border);
        g.drawRect(x + 1, y + 1, 11, 11);
        if (strike != null) {
            g.setColor(strike);
            g.drawLine(x + 1, y + 12, x + 12, y + 1);
        }
        g.setColor(old);
    }
}
