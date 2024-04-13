/*******************************************************************************
 * Copyright (c) 2024 Red Hat Inc. and others.
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
package com.redhat.devtools.lsp4ij.features.rename;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.ui.NameSuggestionsField;
import com.intellij.refactoring.ui.RefactoringDialog;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServerBundle;
import com.redhat.devtools.lsp4ij.features.refactoring.WorkspaceEditData;
import com.redhat.devtools.lsp4ij.internal.CompletableFutures;
import com.redhat.devtools.lsp4ij.internal.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.waitUntilDone;

/**
 * LSP rename dialog.
 * <p>
 * This class has some copy/paste of
 * <a href="https://github.com/JetBrains/intellij-community/blob/master/xml/impl/src/com/intellij/xml/refactoring/XmlTagRenameDialog.java">XMLTageRenameDialog</a>
 * adapted for LSP.
 */
class LSPRenameRefactoringDialog extends RefactoringDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(LSPRenameRefactoringDialog.class);

    @NotNull
    private final LSPRenameParams renameParams;

    @NotNull
    private final PsiFile psiFile;

    @NotNull
    private final Editor editor;

    private JLabel myTitleLabel;
    private NameSuggestionsField myNameSuggestionsField;
    private NameSuggestionsField.DataChanged myNameChangedListener;

    protected LSPRenameRefactoringDialog(@NotNull LSPRenameParams renameParams,
                                         @NotNull PsiFile psiFile,
                                         @NotNull Editor editor) {
        super(psiFile.getProject(), false);
        this.renameParams = renameParams;
        this.psiFile = psiFile;
        this.editor = editor;

        setTitle(RefactoringBundle.message("rename.title"));
        createNewNameComponent();

        init();

        myTitleLabel.setText(LanguageServerBundle.message("lsp.refactor.rename.symbol.dialog.title", renameParams.getNewName()));

        validateButtons();

    }

    private void createNewNameComponent() {
        myNameSuggestionsField = new NameSuggestionsField(new String[]{renameParams.getNewName()}, myProject, FileTypes.PLAIN_TEXT, editor);
        myNameChangedListener = () -> validateButtons();
        myNameSuggestionsField.addDataChangedListener(myNameChangedListener);
    }

    @Override
    protected void doAction() {
        renameParams.setNewName(getNewName());
        doRename(renameParams, psiFile);
        close(DialogWrapper.OK_EXIT_CODE);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return null;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return myNameSuggestionsField.getFocusableComponent();
    }


    @Override
    protected JComponent createNorthPanel() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        myTitleLabel = new JLabel();
        panel.add(myTitleLabel);
        panel.add(Box.createVerticalStrut(8));
        panel.add(myNameSuggestionsField.getComponent());

        return panel;
    }

    public String getNewName() {
        return myNameSuggestionsField.getEnteredName().trim();
    }

    @Override
    protected void validateButtons() {
        super.validateButtons();

        getPreviewAction().setEnabled(false);
    }

    @Override
    protected boolean areButtonsValid() {
        final String newName = getNewName();
        return StringUtils.isNotBlank(newName);
    }

    /**
     * Consume LSP 'textDocument/rename' request and apply the {@link org.eclipse.lsp4j.WorkspaceEdit}.
     * @param renameParams the rename parameters.
     * @param psiFile the Psi file.
     */
    private static void doRename(@NotNull LSPRenameParams renameParams,
                                 @NotNull PsiFile psiFile) {

        CompletableFuture<List<WorkspaceEditData>> future = LSPFileSupport.getSupport(psiFile)
                .getRenameSupport()
                .getRename(renameParams);

        try {
            // Wait upon the future is finished and stop the wait if there are some ProcessCanceledException.
            waitUntilDone(future, psiFile);
        } catch (CancellationException | ProcessCanceledException e) {
            return;
        } catch (ExecutionException e) {
            LOGGER.error("Error while consuming LSP 'textDocument/rename' request", e);
            return;
        }

        if (CompletableFutures.isDoneNormally(future)) {
            List<WorkspaceEditData> workspaceEdits = future.getNow(Collections.emptyList());
            if (!workspaceEdits.isEmpty()) {
                WriteCommandAction.runWriteCommandAction(psiFile.getProject(), () -> {
                    workspaceEdits.forEach(workspaceEditData -> {
                        LSPIJUtils.applyWorkspaceEdit(workspaceEditData.edit());
                    });
                });
            }
        }
    }

    @Override
    protected boolean hasHelpAction() {
        return false;
    }

    @Override
    protected void dispose() {
        myNameSuggestionsField.removeDataChangedListener(myNameChangedListener);
        super.dispose();
    }

}
