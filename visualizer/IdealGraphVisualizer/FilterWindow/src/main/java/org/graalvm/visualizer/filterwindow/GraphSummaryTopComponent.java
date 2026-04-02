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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import jdk.graal.compiler.graphio.parsing.Builder;
import jdk.graal.compiler.graphio.parsing.model.InputGraph;
import jdk.graal.compiler.graphio.parsing.model.InputNode;
import org.graalvm.visualizer.data.services.GraphViewer;
import org.graalvm.visualizer.data.services.InputGraphProvider;
import org.graalvm.visualizer.graph.Diagram;
import org.graalvm.visualizer.graph.Figure;
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
        "COL_NodeCount=Count"
})
public final class GraphSummaryTopComponent extends TopComponent {
    static final String PREFERRED_ID = "GraphSummaryTopComponent";

    private final JLabel graphNameLabel = new JLabel();
    private final JLabel totalsLabel = new JLabel();
    private final NodeTypeSummaryTableModel tableModel = new NodeTypeSummaryTableModel();
    private final JTable table = new JTable(tableModel);

    private ActiveGraphSynchronizer synchronizer;

    public GraphSummaryTopComponent() {
        setName(Bundle.CTL_GraphSummaryTopComponent());
        setToolTipText(Bundle.HINT_GraphSummaryTopComponent());
        initComponents();
        ensureSynchronizer();
        updateSummary(null, null);
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
        table.setRowSelectionAllowed(false);
        table.setColumnSelectionAllowed(false);
        table.setCellSelectionEnabled(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Color.class, new ColorSwatchRenderer());
        table.getColumnModel().getColumn(0).setMinWidth(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(22);
        table.getColumnModel().getColumn(0).setMaxWidth(22);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setMaxWidth(120);

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

    private void updateSummary(DiagramViewer viewer, InputGraph graph) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updateSummary(viewer, graph));
            return;
        }

        if (graph == null) {
            setGraphNameText(Bundle.LBL_NoActiveGraph());
            totalsLabel.setText(" ");
            tableModel.setRows(List.of());
            return;
        }

        Map<String, Integer> counts = new HashMap<>();
        Map<String, Map<Color, Integer>> colorCounts = new HashMap<>();
        Diagram diagram = viewer == null ? null : viewer.getModel().getDiagramToView();

        if (diagram == null) {
            for (InputNode node : graph.getNodes()) {
                counts.merge(nodeType(node), 1, Integer::sum);
            }
        } else {
            diagram.render(() -> {
                for (InputNode node : graph.getNodes()) {
                    String type = nodeType(node);
                    counts.merge(type, 1, Integer::sum);
                    Color color = resolveNodeColor(diagram, node);
                    if (color != null) {
                        colorCounts.computeIfAbsent(type, t -> new HashMap<>()).merge(color, 1, Integer::sum);
                    }
                }
            });
        }

        List<NodeTypeRow> rows = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            rows.add(new NodeTypeRow(entry.getKey(), entry.getValue(), dominantColor(colorCounts.get(entry.getKey()))));
        }
        rows.sort(Comparator
                .comparingInt(NodeTypeRow::count).reversed()
                .thenComparing(NodeTypeRow::nodeType, String.CASE_INSENSITIVE_ORDER));

        setGraphNameText(Bundle.LBL_CurrentGraph(graph.getName()));
        totalsLabel.setText(Bundle.LBL_SummaryCounts(graph.getNodeCount(), rows.size()));
        tableModel.setRows(rows);
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

    private String nodeType(InputNode node) {
        String type = node.getProperties().getString(PROPNAME_CLASS, null);
        if (type != null && !type.isBlank()) {
            return type;
        }
        Builder.NodeClass nodeClass = node.getNodeClass();
        if (nodeClass != null && nodeClass.className != null && !nodeClass.className.isBlank()) {
            return nodeClass.className;
        }
        String name = node.getProperties().getString(PROPNAME_NAME, null);
        if (name != null && !name.isBlank()) {
            return name;
        }
        return Bundle.LBL_UnknownNodeType();
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
            updateSummary(activeViewer, graph != null ? graph : viewerService.getActiveGraph());
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
            updateSummary(viewer, ev.getModel().getGraphToView());
        }

        @Override
        public void diagramReady(DiagramViewerEvent ev) {
            updateSummary(viewer, ev.getModel().getGraphToView());
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

        void setRows(List<NodeTypeRow> newRows) {
            rows = List.copyOf(newRows);
            fireTableDataChanged();
        }
    }

    private static final class NodeTypeRow {
        private final String nodeType;
        private final int count;
        private final Color color;

        NodeTypeRow(String nodeType, int count, Color color) {
            this.nodeType = nodeType;
            this.count = count;
            this.color = color;
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
