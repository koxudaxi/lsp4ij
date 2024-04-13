/*******************************************************************************
 * Copyright (c) 2024 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.features.rename;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LSPRequestConstants;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.features.AbstractLSPFeatureSupport;
import com.redhat.devtools.lsp4ij.internal.CancellationSupport;
import com.redhat.devtools.lsp4ij.internal.CompletableFutures;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * LSP prepare rename support which loads and caches prepare rename by consuming:
 *
 * <ul>
 *     <li>LSP 'textDocument/prepareRename' requests</li>
 * </ul>
 */
public class LSPPrepareRenameSupport extends AbstractLSPFeatureSupport<LSPPrepareRenameParams, List<PrepareRenameResultData>> {
    private int previousOffset = -1;

    public LSPPrepareRenameSupport(@NotNull PsiFile file) {
        super(file);
    }

    public CompletableFuture<List<PrepareRenameResultData>> getPrepareRenameResult(LSPPrepareRenameParams params) {
        int offset = params.getOffset();
        if (previousOffset != offset) {
            super.cancel();
        }
        previousOffset = offset;
        return super.getFeatureData(params);
    }

    @Override
    protected CompletableFuture<List<PrepareRenameResultData>> doLoad(@NotNull LSPPrepareRenameParams params,
                                                                      @NotNull CancellationSupport cancellationSupport) {
        PsiFile file = super.getFile();
        return getPrepareRenameResult(file.getVirtualFile(), file.getProject(), params, cancellationSupport);
    }

    private static @NotNull CompletableFuture<List<PrepareRenameResultData>> getPrepareRenameResult(@NotNull VirtualFile file,
                                                                                                    @NotNull Project project,
                                                                                                    @NotNull LSPPrepareRenameParams params,
                                                                                                    @NotNull CancellationSupport cancellationSupport) {

        return LanguageServiceAccessor.getInstance(project)
                .getLanguageServers(file, LanguageServerItem::isRenameSupported)
                .thenComposeAsync(languageServers -> {
                    // Here languageServers is the list of language servers which matches the given file
                    // and which have 'rename' support
                    if (languageServers.isEmpty()) {
                        return CompletableFuture.completedFuture(Collections.emptyList());
                    }

                    List<CompletableFuture<List<PrepareRenameResultData>>> prepareRenamePerServerFutures = new ArrayList<>();
                    DefaultPrepareRenameResultProvider defaultPrepareRenameResult = new DefaultPrepareRenameResultProvider(params);
                    for (var languageServer : languageServers) {
                        if (languageServer.isPrepareRenameSupported()) {
                            prepareRenamePerServerFutures.add(getPrepareRenamesFor(params, defaultPrepareRenameResult, languageServer, cancellationSupport));
                        } else {
                            prepareRenamePerServerFutures.add(CompletableFuture.completedFuture(List.of(defaultPrepareRenameResult.apply(languageServer))));
                        }
                    }
                    // Merge list of textDocument/prepareRename future in one future which return the list of color information
                    return CompletableFutures.mergeInOneFuture(prepareRenamePerServerFutures, cancellationSupport);
                });
    }

    private static CompletableFuture<List<PrepareRenameResultData>> getPrepareRenamesFor(@NotNull PrepareRenameParams params,
                                                                                         @NotNull DefaultPrepareRenameResultProvider defaultPrepareRenameResultProvider,
                                                                                         @NotNull LanguageServerItem languageServer,
                                                                                         @NotNull CancellationSupport cancellationSupport) {
        return cancellationSupport.execute(languageServer
                        .getTextDocumentService()
                        .prepareRename(params), languageServer, LSPRequestConstants.TEXT_DOCUMENT_PREPARE_RENAME)
                .thenApplyAsync(prepareRename -> {
                    PrepareRenameResultData result = getPrepareRenameResultData(defaultPrepareRenameResultProvider, languageServer, prepareRename);
                    return List.of(result);
                });
    }

    @Nullable
    private static PrepareRenameResultData getPrepareRenameResultData(@NotNull DefaultPrepareRenameResultProvider defaultPrepareRenameResultProvider,
                                                                      @NotNull LanguageServerItem languageServer,
                                                                      @Nullable Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> prepareRename) {
        if (prepareRename != null) {
            Range range = null;
            String placeholder = null;
            if (prepareRename.isFirst()) {
                range = prepareRename.getFirst();
            } else if (prepareRename.isSecond()) {
                PrepareRenameResult prepareRenameResult = prepareRename.getSecond();
                range = prepareRenameResult.getRange();
                placeholder = prepareRenameResult.getPlaceholder();
            }
            var document = defaultPrepareRenameResultProvider.getDocument();
            var textRange = range != null ? LSPIJUtils.toTextRange(range, document) : defaultPrepareRenameResultProvider.getTextRange();
            if (placeholder == null) {
                placeholder = document.getText(textRange);
            }
            return new PrepareRenameResultData(textRange, placeholder, languageServer);
        }
        return defaultPrepareRenameResultProvider.apply(languageServer);
    }


}
