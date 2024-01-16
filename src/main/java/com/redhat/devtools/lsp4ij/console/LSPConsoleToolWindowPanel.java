/*******************************************************************************
 * Copyright (c) 2023 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package com.redhat.devtools.lsp4ij.console;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.OnePixelDivider;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.CardLayoutPanel;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI;
import com.redhat.devtools.lsp4ij.LanguageServerBundle;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.console.explorer.LanguageServerExplorer;
import com.redhat.devtools.lsp4ij.console.explorer.LanguageServerProcessTreeNode;
import com.redhat.devtools.lsp4ij.console.explorer.LanguageServerTreeNode;
import com.redhat.devtools.lsp4ij.launching.UserDefinedLanguageServerSettings;
import com.redhat.devtools.lsp4ij.settings.ui.CommandLineWidget;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinition;
import com.redhat.devtools.lsp4ij.server.definition.LanguageServerDefinitionListener;
import com.redhat.devtools.lsp4ij.server.definition.launching.UserDefinedLanguageServerDefinition;
import com.redhat.devtools.lsp4ij.settings.ServerTrace;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * LSP consoles
 */
public class LSPConsoleToolWindowPanel extends SimpleToolWindowPanel implements Disposable {

    private final Project project;

    private LanguageServerExplorer explorer;

    private ConsolesPanel consoles;
    private boolean disposed;

    public LSPConsoleToolWindowPanel(Project project) {
        super(false, true);
        this.project = project;
        createUI();
    }

    private void createUI() {
        explorer = new LanguageServerExplorer(this);
        var scrollPane = new JBScrollPane(explorer);
        this.consoles = new ConsolesPanel();
        var splitPane = createSplitPanel(scrollPane, consoles);
        super.setContent(splitPane);
        super.revalidate();
        super.repaint();
        explorer.load();
    }

    public Project getProject() {
        return project;
    }

    private static JComponent createSplitPanel(JComponent left, JComponent right) {
        OnePixelSplitter splitter = new OnePixelSplitter(false, 0.15f);
        splitter.setShowDividerControls(true);
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setFirstComponent(left);
        splitter.setSecondComponent(right);
        return splitter;
    }

    public void selectDetail(LanguageServerTreeNode treeNode) {
        if (consoles == null || isDisposed()) {
            return;
        }
        consoles.select(treeNode, true);
    }

    public void selectConsole(LanguageServerProcessTreeNode processTreeNode) {
        if (consoles == null || isDisposed()) {
            return;
        }
        consoles.select(processTreeNode, true);
    }

    /**
     * A card-panel that displays panels for each language server instances.
     */
    private class ConsolesPanel extends CardLayoutPanel<DefaultMutableTreeNode, DefaultMutableTreeNode, ConsoleContentPanel> {

        @Override
        protected DefaultMutableTreeNode prepare(DefaultMutableTreeNode key) {
            return key;
        }

        @Override
        protected ConsoleContentPanel create(DefaultMutableTreeNode key) {
            if (isDisposed() || LSPConsoleToolWindowPanel.this.isDisposed()) {
                return null;
            }
            return new ConsoleContentPanel(key);
        }

        @Override
        public void dispose() {
            removeAll();
        }

        @Override
        protected void dispose(DefaultMutableTreeNode key, ConsoleContentPanel value) {
            if (value != null) {
                value.dispose();
            }
        }
    }

    private class ConsoleContentPanel extends SimpleCardLayoutPanel<JComponent> {

        private static final String NAME_VIEW_CONSOLE = "console";

        private static final String NAME_VIEW_DETAIL = "detail";

        private ConsoleView consoleView;

        private final Set<Runnable> settingsChangeListeners = new HashSet<>();

        private Set<LanguageServerDefinitionListener> serverDefinitionListeners = new HashSet<>();


        public ConsoleContentPanel(DefaultMutableTreeNode key) {
            if (key instanceof LanguageServerTreeNode) {
                add(createDetailPanel((LanguageServerTreeNode) key), NAME_VIEW_DETAIL);
                showDetail();
            } else if (key instanceof LanguageServerProcessTreeNode) {
                consoleView = createConsoleView(((LanguageServerProcessTreeNode) key).getLanguageServer().serverDefinition, project);
                JComponent consoleComponent = consoleView.getComponent();
                Disposer.register(LSPConsoleToolWindowPanel.this, consoleView);
                add(consoleComponent, NAME_VIEW_CONSOLE);
                configureToolbar(consoleComponent);
                showConsole();
            }
        }

        /**
         * Configure console toolbar on the right of the console to provide some action like "Scroll to End", "Clean", etc
         *
         * @param consoleComponent
         */
        private void configureToolbar(JComponent consoleComponent) {
            DefaultActionGroup myToolbarActions = new DefaultActionGroup();
            myToolbarActions.addAll(consoleView.createConsoleActions());

            ActionToolbar tb = ActionManager.getInstance().createActionToolbar("LSP Console", myToolbarActions, false);
            tb.setTargetComponent(consoleComponent);
            tb.getComponent().setBorder(JBUI.Borders.merge(tb.getComponent().getBorder(), JBUI.Borders.customLine(OnePixelDivider.BACKGROUND, 0, 0, 0, 1), true));
            consoleComponent.add(tb.getComponent(), BorderLayout.EAST);
        }

        private JComponent createDetailPanel(LanguageServerTreeNode key) {
            LanguageServerDefinition serverDefinition = key.getServerDefinition();
            Project project = LSPConsoleToolWindowPanel.this.project;

            // Create Server Trace
            ComboBox<ServerTrace> serverTraceComboBox = new ComboBox<>(new DefaultComboBoxModel<>(ServerTrace.values()));
            com.redhat.devtools.lsp4ij.settings.UserDefinedLanguageServerSettings.LanguageServerDefinitionSettings initialSettings = com.redhat.devtools.lsp4ij.settings.UserDefinedLanguageServerSettings.getInstance(project).getLanguageServerSettings(serverDefinition.id);
            if (initialSettings != null && initialSettings.getServerTrace() != null) {
                serverTraceComboBox.setSelectedItem(initialSettings.getServerTrace());
            }
            serverTraceComboBox.addItemListener(event -> {
                ServerTrace serverTrace = (ServerTrace) event.getItem();
                com.redhat.devtools.lsp4ij.settings.UserDefinedLanguageServerSettings.LanguageServerDefinitionSettings settings = com.redhat.devtools.lsp4ij.settings.UserDefinedLanguageServerSettings.getInstance(project).getLanguageServerSettings(serverDefinition.id);
                if (settings == null) {
                    settings = new com.redhat.devtools.lsp4ij.settings.UserDefinedLanguageServerSettings.LanguageServerDefinitionSettings();
                }
                settings.setServerTrace(serverTrace);
                com.redhat.devtools.lsp4ij.settings.UserDefinedLanguageServerSettings.getInstance(project).setLanguageServerSettings(serverDefinition.id, settings);
            });

            // Add a settings listener to keep serverTraceComboBox in sync with changes coming from the LSP settings page
            // See https://github.com/redhat-developer/intellij-quarkus/issues/1062
            Runnable settingsChangeListener = createSettingsChangeListener(serverDefinition.id, serverTraceComboBox);
            com.redhat.devtools.lsp4ij.settings.UserDefinedLanguageServerSettings.getInstance(getProject()).addChangeHandler(settingsChangeListener);
            settingsChangeListeners.add(settingsChangeListener);

            // Create command line if needed
            CommandLineWidget commandLine = null;
            if (serverDefinition instanceof UserDefinedLanguageServerDefinition userDefinedLanguageServerDefinition) {
                commandLine = new CommandLineWidget();
                UserDefinedLanguageServerSettings.UserDefinedLanguageServerItemSettings launchSettings = UserDefinedLanguageServerSettings.getInstance().getLaunchConfigSettings(serverDefinition.id);
                if (launchSettings != null) {
                    commandLine.setText(launchSettings.getCommandLine() != null? launchSettings.getCommandLine() : "");
                    final var c= commandLine;
                    commandLine.getDocument().addDocumentListener(new DocumentAdapter() {
                        @Override
                        protected void textChanged(@NotNull DocumentEvent e) {
                            launchSettings.setCommandLine(c.getText());
                            userDefinedLanguageServerDefinition.setCommandLine(c.getText());
                        }
                    });
                }
                var serverDefinitionListener = createServerDefinitionListener(serverDefinition, commandLine);
                LanguageServersRegistry.getInstance().addLanguageServerDefinitionListener(serverDefinitionListener);
                serverDefinitionListeners.add(serverDefinitionListener);
            }

            FormBuilder builder = FormBuilder.createFormBuilder()
                    .setFormLeftIndent(10)
                    .addComponent(createTitleComponent(serverDefinition), 1)
                    .addLabeledComponent(LanguageServerBundle.message("language.server.trace"), serverTraceComboBox, 1);

            if (commandLine != null) {
                builder.addLabeledComponent(LanguageServerBundle.message("new.language.server.dialog.command"), commandLine, true);
            }
            return builder.addComponentFillVertically(new JPanel(), 0)
                    .getPanel();
        }

        private Runnable createSettingsChangeListener(String id, ComboBox<ServerTrace> serverTraceComboBox) {
            return new Runnable() {
                @Override
                public void run() {
                    if (isDisposed()) {
                        return;
                    }
                    com.redhat.devtools.lsp4ij.settings.UserDefinedLanguageServerSettings.LanguageServerDefinitionSettings settings = com.redhat.devtools.lsp4ij.settings.UserDefinedLanguageServerSettings.getInstance(project).getLanguageServerSettings(id);
                    if (settings == null) { //No settings have been set yet for this particular LS
                        return;
                    }
                    ServerTrace newServerTrace = settings.getServerTrace();
                    if (newServerTrace != null && !newServerTrace.equals(serverTraceComboBox.getSelectedItem())) {
                        serverTraceComboBox.setSelectedItem(newServerTrace);
                    }
                }
            };
        }

        @NotNull
        private static LanguageServerDefinitionListener createServerDefinitionListener(LanguageServerDefinition serverDefinition, CommandLineWidget commandLine) {
            return new LanguageServerDefinitionListener() {
                @Override
                public void handleAdded(@NotNull LanguageServerDefinitionListener.LanguageServerAddedEvent event) {
                    // Do nothing
                }

                @Override
                public void handleRemoved(@NotNull LanguageServerDefinitionListener.LanguageServerRemovedEvent event) {
                    // Do nothing
                }

                @Override
                public void handleChanged(@NotNull LanguageServerDefinitionListener.LanguageServerChangedEvent event) {
                    if (event.commandChanged
                            && event.serverDefinition.equals(serverDefinition)
                            && !Objects.equals(((UserDefinedLanguageServerDefinition) serverDefinition).getCommandLine(), commandLine.getText())) {
                        commandLine.setText(((UserDefinedLanguageServerDefinition) serverDefinition).getCommandLine());
                    }
                }
            };
        }


        private JComponent createTitleComponent(LanguageServerDefinition languageServerDefinition) {
            JLabel title = new JLabel(languageServerDefinition.getDisplayName());
            String description = languageServerDefinition.description;
            if (description != null && description.length() > 0) {
                // @See com.intellij.internal.ui.ComponentPanelTestAction for more details on how to create comment panels
                return UI.PanelFactory.panel(title)
                        .withComment(description)
                        .createPanel();
            }
            return title;
        }

        private void showConsole() {
            show(NAME_VIEW_CONSOLE);
        }

        private void showDetail() {
            show(NAME_VIEW_DETAIL);
        }

        public void showMessage(String message) {
            if (consoleView == null) {
                return;
            }
            consoleView.print(message, ConsoleViewContentType.SYSTEM_OUTPUT);
        }

        public void showError(Throwable exception) {
            if (consoleView == null) {
                return;
            }
            String stacktrace = getStackTrace(exception);
            consoleView.print(stacktrace, ConsoleViewContentType.ERROR_OUTPUT);
        }

        @Override
        public void dispose() {
            for (var settingsChangeListener : settingsChangeListeners) {
                com.redhat.devtools.lsp4ij.settings.UserDefinedLanguageServerSettings.getInstance(getProject()).removeChangeHandler(settingsChangeListener);
            }
            settingsChangeListeners.clear();
            for (var serverDefinitionListener : serverDefinitionListeners) {
                LanguageServersRegistry.getInstance().removeLanguageServerDefinitionListener(serverDefinitionListener);
            }
            serverDefinitionListeners.clear();
            super.dispose();
            if (consoleView != null) {
                consoleView.dispose();
            }
        }
    }

    private ConsoleView createConsoleView(@NotNull LanguageServerDefinition serverDefinition, @NotNull Project project) {
        var builder = new LSPTextConsoleBuilderImpl(serverDefinition, project);
        builder.setViewer(true);
        return builder.getConsole();
    }

    public void showMessage(LanguageServerProcessTreeNode processTreeNode, String message) {
        if (isDisposed()) {
            return;
        }
        var consoleOrErrorPanel = consoles.getValue(processTreeNode, true);
        if (consoleOrErrorPanel != null) {
            consoleOrErrorPanel.showMessage(message);
        }
    }

    public void showError(LanguageServerProcessTreeNode processTreeNode, Throwable exception) {
        if (isDisposed()) {
            return;
        }
        var consoleOrErrorPanel = consoles.getValue(processTreeNode, true);
        if (consoleOrErrorPanel != null) {
            consoleOrErrorPanel.showError(exception);
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        if (consoles != null) {
            consoles.dispose();
        }
        explorer.dispose();
    }

    private boolean isDisposed() {
        return disposed || project.isDisposed();
    }

    /**
     * Code copied from https://github.com/apache/commons-lang/blob/24744a40b2c094945e542b71cc1fbf59caa0d70b/src/main/java/org/apache/commons/lang3/exception/ExceptionUtils.java#L400C5-L407C6
     *
     * @param throwable
     * @return
     */
    private static String getStackTrace(final Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        final StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw, true));
        return sw.toString();
    }
}
