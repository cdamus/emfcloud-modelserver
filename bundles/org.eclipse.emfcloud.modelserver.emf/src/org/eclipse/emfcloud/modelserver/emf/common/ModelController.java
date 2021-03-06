/********************************************************************************
 * Copyright (c) 2019 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0, or the MIT License which is
 * available at https://opensource.org/licenses/MIT.
 *
 * SPDX-License-Identifier: EPL-2.0 OR MIT
 ********************************************************************************/
package org.eclipse.emfcloud.modelserver.emf.common;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.apache.log4j.Logger;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emfcloud.modelserver.command.CCommand;
import org.eclipse.emfcloud.modelserver.common.codecs.DecodingException;
import org.eclipse.emfcloud.modelserver.common.codecs.EMFJsonConverter;
import org.eclipse.emfcloud.modelserver.common.codecs.EncodingException;
import org.eclipse.emfcloud.modelserver.emf.common.codecs.CodecsManager;
import org.eclipse.emfcloud.modelserver.emf.common.codecs.JsonCodec;
import org.eclipse.emfcloud.modelserver.emf.configuration.ServerConfiguration;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Maps;
import com.google.inject.Inject;

import io.javalin.http.Context;
import io.javalin.plugin.json.JavalinJackson;

public class ModelController {

   private static final Logger LOG = Logger.getLogger(ModelController.class.getSimpleName());

   private final ModelRepository modelRepository;
   private final SessionController sessionController;
   private final ServerConfiguration serverConfiguration;
   private final CodecsManager codecs;

   @Inject
   public ModelController(final ModelRepository modelRepository, final SessionController sessionController,
      final ServerConfiguration serverConfiguration, final CodecsManager codecs) {

      JavalinJackson.configure(EMFJsonConverter.setupDefaultMapper());
      this.modelRepository = modelRepository;
      this.sessionController = sessionController;
      this.serverConfiguration = serverConfiguration;
      this.codecs = codecs;
   }

   public void create(final Context ctx, final String modeluri) {
      readPayload(ctx).ifPresentOrElse(
         eObject -> {
            try {
               this.modelRepository.addModel(modeluri, eObject);
               final JsonNode encoded = codecs.encode(ctx, eObject);
               ctx.json(JsonResponse.success(encoded));
               this.sessionController.modelChanged(modeluri);
            } catch (EncodingException ex) {
               handleEncodingError(ctx, ex);
            } catch (IOException e) {
               handleError(ctx, 500, "Could not save resource");
            }
         },
         () -> handleError(ctx, 400, "Create new model failed"));
   }

   public void delete(final Context ctx, final String modeluri) {
      if (this.modelRepository.hasModel(modeluri)) {
         try {
            this.modelRepository.removeModel(modeluri);
            ctx.json(JsonResponse.success("Model '" + modeluri + "' successfully deleted"));
            this.sessionController.modelDeleted(modeluri);
         } catch (IOException e) {
            handleError(ctx, 404, "Model '" + modeluri + "' not found, cannot be deleted!");
         }
      } else {
         handleError(ctx, 404, "Model '" + modeluri + "' not found, cannot be deleted!");
      }
   }

   public void getAll(final Context ctx) {
      try {
         final Map<URI, EObject> allModels = this.modelRepository.getAllModels();
         Map<URI, JsonNode> encodedEntries = Maps.newLinkedHashMap();
         for (Map.Entry<URI, EObject> entry : allModels.entrySet()) {
            final JsonNode encoded = codecs.encode(ctx, entry.getValue());
            encodedEntries.put(entry.getKey(), encoded);
         }
         ctx.json(JsonResponse.success(JsonCodec.encode(encodedEntries)));
      } catch (EncodingException ex) {
         handleEncodingError(ctx, ex);
      } catch (IOException e) {
         handleError(ctx, 404, "Could not load all models");
      }
   }

   public void getOne(final Context ctx, final String modeluri) {
      this.modelRepository.getModel(modeluri).ifPresentOrElse(
         model -> {
            if (model == null) {
               ctx.json(JsonResponse.error(""));
            } else {
               try {
                  ctx.json(JsonResponse.success(codecs.encode(ctx, model)));
               } catch (EncodingException ex) {
                  handleEncodingError(ctx, ex);
               }
            }
         },
         () -> handleError(ctx, 404, "Model '" + modeluri + "' not found!"));
   }

   public void getModelElementById(final Context ctx, final String modeluri, final String elementid) {
      this.modelRepository.getModelElementById(modeluri, elementid).ifPresentOrElse(
         modelElement -> {
            try {
               ctx.json(JsonResponse.success(codecs.encode(ctx, modelElement)));
            } catch (EncodingException ex) {
               handleEncodingError(ctx, ex);
            }
         },
         () -> handleError(ctx, 404,
            "Element by elementid '" + elementid + "' of model '" + modeluri + "' not found!"));
   }

   public void getModelElementByName(final Context ctx, final String modeluri, final String elementname) {
      this.modelRepository.getModelElementByName(modeluri, elementname).ifPresentOrElse(
         modelElement -> {
            if (modelElement == null) {
               ctx.json(JsonResponse.error(""));
            } else {
               try {
                  ctx.json(JsonResponse.success(codecs.encode(ctx, modelElement)));
               } catch (EncodingException ex) {
                  handleEncodingError(ctx, ex);
               }
            }
         },
         () -> handleError(ctx, 404,
            "Element by elementname '" + elementname + "' of model '" + modeluri + "' not found!"));
   }

   public void update(final Context ctx, final String modeluri) {
      readPayload(ctx).ifPresentOrElse(
         eObject -> modelRepository.updateModel(modeluri, eObject)
            .ifPresentOrElse(__ -> {
               try {
                  ctx.json(JsonResponse.fullUpdate(codecs.encode(ctx, eObject)));
               } catch (EncodingException e) {
                  handleEncodingError(ctx, e);
               }
               sessionController.modelChanged(modeluri);
            },
               () -> handleError(ctx, 404, "No such model resource to update")),
         () -> handleError(ctx, 400, "Update has no content"));
   }

   public void save(final Context ctx, final String modeluri) {
      if (this.modelRepository.saveModel(modeluri)) {
         ctx.json(JsonResponse.success("Model '" + modeluri + "' successfully saved"));
         sessionController.modelSaved(modeluri);
      } else {
         handleError(ctx, 500, "Saving model '" + modeluri + "' failed!");
      }
   }

   public void saveAll(final Context ctx) {
      if (this.modelRepository.saveAllModels()) {
         ctx.json(JsonResponse.success("All models successfully saved"));
         sessionController.allModelSaved();
      } else {
         handleError(ctx, 500, "Saving all models failed!");
      }
   }

   public void undo(final Context ctx, final String modeluri) {
      CCommand undoCommand = modelRepository.getUndoCommand(modeluri);
      if (undoCommand != null) {
         /*
          * In order to encode certain commands (e.g., commands that remove model elements) we need all element
          * information,
          * e.g., the info which element was removed before it is gone. Therefore we encode the command in all formats
          * and provide them to the model repository which will select the correct format for each subscribed client.
          * This means that we encode the command once for all formats beforehand. The alternative would be to encode it
          * once per subscribed client.
          * The same applies to the redo method below.
          */
         Map<String, JsonNode> encodings = sessionController.getCommandEncodings(modeluri, undoCommand);
         boolean undoSuccess = modelRepository.undo(modeluri);
         if (undoSuccess) {
            ctx.json(JsonResponse.success("Successful undo."));
            sessionController.modelChanged(modeluri, encodings);
            return;
         }
      }
      handleWarning(ctx, 202, "Cannot undo.");
   }

   public void redo(final Context ctx, final String modeluri) {
      CCommand redoCommand = modelRepository.getRedoCommand(modeluri);
      if (redoCommand != null) {
         /* Please see comment in undo method */
         Map<String, JsonNode> encodings = sessionController.getCommandEncodings(modeluri, redoCommand);
         boolean redoSuccess = modelRepository.redo(modeluri);
         if (redoSuccess) {
            ctx.json(JsonResponse.success("Successful redo."));
            sessionController.modelChanged(modeluri, encodings);
            return;
         }
      }
      handleWarning(ctx, 202, "Cannot redo");
   }

   public void getModelUris(final Context ctx) {
      try {
         ctx.json(JsonResponse.success(JsonCodec.encode(this.modelRepository.getAllModelUris())));
      } catch (EncodingException ex) {
         handleEncodingError(ctx, ex);
      }
   }

   private Optional<EObject> readPayload(final Context ctx) {
      try {
         JsonNode json = JavalinJackson.getObjectMapper().readTree(ctx.body());
         if (!json.has(JsonResponseMember.DATA)) {
            handleError(ctx, 400, "Empty JSON");
            return Optional.empty();
         }
         JsonNode jsonDataNode = json.get(JsonResponseMember.DATA);
         String jsonData = !jsonDataNode.asText().isEmpty() ? jsonDataNode.asText() : jsonDataNode.toString();
         if (jsonData.equals("{}")) {
            handleError(ctx, 400, "Empty JSON");
            return Optional.empty();
         }

         return codecs.decode(ctx, jsonData, serverConfiguration.getWorkspaceRootURI());
      } catch (DecodingException | IOException e) {
         handleError(ctx, 400, "Invalid JSON");
      }
      return Optional.empty();
   }

   public void executeCommand(final Context ctx, final String modelURI) {
      this.modelRepository.getModel(modelURI).ifPresentOrElse(
         model -> {
            if (model == null) {
               handleError(ctx, 404, String.format("Model '%s' not found!", modelURI));
            } else {
               readPayload(ctx).filter(CCommand.class::isInstance)//
                  .map(CCommand.class::cast) //
                  .ifPresent(cmd -> {
                     try {
                        // Create a temporary resource in the workspace
                        // so that we can resolve cross-references into
                        // the user model from the command(s)
                        URI uri = URI.createURI("$command.res")
                           .resolve(serverConfiguration.getWorkspaceRootURI());
                        Resource resource = new ResourceImpl(uri);
                        modelRepository.addTemporaryCommandResource(modelURI, resource, cmd);

                        try {
                           EcoreUtil.resolveAll(resource);
                           /*
                            * In a similar way as for undo/redo we encode the command beforehand to ensure the encoded
                            * command for notifying subscribed client contains all necessary element information,
                            * e.g.the original owner in case of a SET command that changes the name and therefore its
                            * semantic URI
                            */
                           Map<String, JsonNode> encodings = sessionController.getCommandEncodings(modelURI, cmd);
                           modelRepository.updateModel(modelURI, cmd);
                           sessionController.modelChanged(modelURI, encodings);
                           ctx.json(JsonResponse.success());
                        } finally {
                           resource.unload();
                           modelRepository.removeTemporaryCommandResource(modelURI, resource);
                        }
                     } catch (DecodingException e) {
                        handleDecodingError(ctx, e);
                     }
                  });
               ctx.json(JsonResponse.success("Model '" + modelURI + "' successfully updated"));
            }
         },
         () -> handleError(ctx, 404, String.format("Model '%s' not found!", modelURI)));
   }

   private void handleWarning(final Context ctx, final int statusCode, final String warnMsg) {
      LOG.warn(warnMsg);
      ctx.status(statusCode).json(JsonResponse.warning(warnMsg));
   }

   private void handleEncodingError(final Context context, final EncodingException ex) {
      handleError(context, 500, "An error occurred during data encoding", ex);
   }

   private void handleDecodingError(final Context context, final DecodingException ex) {
      handleError(context, 500, "An error occurred during data decoding", ex);
   }

   private void handleError(final Context ctx, final int statusCode, final String errorMsg) {
      LOG.error(errorMsg);
      ctx.status(statusCode).json(JsonResponse.error(errorMsg));
   }

   private void handleError(final Context ctx, final int statusCode, final String errorMsg, final Exception e) {
      LOG.error(errorMsg, e);
      ctx.status(statusCode).json(JsonResponse.error(errorMsg));
   }
}
