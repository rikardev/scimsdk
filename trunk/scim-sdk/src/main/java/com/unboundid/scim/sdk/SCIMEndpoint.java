/*
 * Copyright 2011-2012 UnboundID Corp.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPLv2 only)
 * or the terms of the GNU Lesser General Public License (LGPLv2.1 only)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 */

package com.unboundid.scim.sdk;

import com.unboundid.scim.data.Meta;
import com.unboundid.scim.data.ResourceFactory;
import com.unboundid.scim.data.BaseResource;
import com.unboundid.scim.marshal.Marshaller;
import com.unboundid.scim.marshal.Unmarshaller;
import com.unboundid.scim.marshal.json.JsonMarshaller;
import com.unboundid.scim.marshal.json.JsonUnmarshaller;
import com.unboundid.scim.marshal.xml.XmlMarshaller;
import com.unboundid.scim.marshal.xml.XmlUnmarshaller;
import com.unboundid.scim.schema.ResourceDescriptor;
import org.apache.wink.client.ClientResponse;
import org.apache.wink.client.RestClient;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;

/**
 * This class represents a SCIM endpoint (ie. Users, Groups, etc.) and handles
 * all protocol-level interactions with the service provider. It acts as a
 * helper class for invoking CRUD operations of resources and processing their
 * results.
 *
 * @param <R> The type of resource instances handled by this SCIMEndpoint.
 */
public class SCIMEndpoint<R extends BaseResource>
{
  private final SCIMService scimService;
  private final ResourceDescriptor resourceDescriptor;
  private final ResourceFactory<R> resourceFactory;
  private final Unmarshaller unmarshaller;
  private final Marshaller marshaller;
  private final MediaType contentType;
  private final MediaType acceptType;
  private final boolean[] overrides = new boolean[3];
  private final RestClient client;

  /**
   * Create a SCIMEndpoint with the provided information.
   *
   * @param scimService The SCIMService to use.
   * @param restClient The Wink REST client.
   * @param resourceDescriptor The resource descriptor of this endpoint.
   * @param resourceFactory The ResourceFactory that should be used to create
   *                        resource instances.
   */
  SCIMEndpoint(final SCIMService scimService,
               final RestClient restClient,
               final ResourceDescriptor resourceDescriptor,
               final ResourceFactory<R> resourceFactory)
  {
    this.scimService = scimService;
    this.client = restClient;
    this.resourceDescriptor = resourceDescriptor;
    this.resourceFactory = resourceFactory;
    this.contentType = scimService.getContentType();
    this.acceptType = scimService.getAcceptType();
    this.overrides[0] = scimService.isOverridePut();
    this.overrides[1] = scimService.isOverridePatch();
    this.overrides[2] = scimService.isOverrideDelete();

    if (scimService.getContentType().equals(MediaType.APPLICATION_JSON_TYPE))
    {
      this.marshaller = new JsonMarshaller();
    }
    else
    {
      this.marshaller = new XmlMarshaller();
    }

    if(scimService.getAcceptType().equals(MediaType.APPLICATION_JSON_TYPE))
    {
      this.unmarshaller = new JsonUnmarshaller();
    }
    else
    {
      this.unmarshaller = new XmlUnmarshaller();
    }
  }



  /**
   * Constructs a new instance of a resource object which is empty. This
   * method does not interact with the SCIM service. It creates a local object
   * that may be provided to the {@link SCIMEndpoint#create} method after the
   * attributes have been specified.
   *
   * @return  A new instance of a resource object.
   */
  public R newResource()
  {
    return resourceFactory.createResource(resourceDescriptor, new SCIMObject());
  }

  /**
   * Retrieves a resource instance given the ID.
   *
   * @param id The ID of the resource to retrieve.
   * @return The retrieved resource.
   * @throws SCIMException If an error occurs.
   */
  public R get(final String id)
      throws SCIMException
  {
    return get(id, null, null);
  }

  /**
   * Retrieves a resource instance given the ID, only if the current version
   * has been modified.
   *
   * @param id The ID of the resource to retrieve.
   * @param etag The entity tag that indicates the entry should be returned
   *             only if the entity tag of the current resource is different
   *             from the provided value. A value of <code>null</code> indicates
   *             unconditional return.
   * @param requestedAttributes The attributes of the resource to retrieve.
   * @return The retrieved resource or <code>null</code> if the requested
   * resource has not been modified.
   * @throws SCIMException If an error occurs.
   */
  public R get(final String id, final String etag,
               final String... requestedAttributes)
      throws SCIMException
  {
    final UriBuilder uriBuilder = UriBuilder.fromUri(scimService.getBaseURL());
    uriBuilder.path(resourceDescriptor.getEndpoint());

    // The ServiceProviderConfig is a special case where the id is not
    // specified.
    if (id != null)
    {
      uriBuilder.path(id);
    }

    URI uri = uriBuilder.build();
    org.apache.wink.client.Resource clientResource = client.resource(uri);
    clientResource.accept(acceptType);
    clientResource.contentType(contentType);
    addAttributesQuery(clientResource, requestedAttributes);
    if(etag != null && !etag.isEmpty())
    {
      clientResource.header("If-None-Match", etag);
    }

    ClientResponse response = clientResource.get();

    InputStream entity = response.getEntity(InputStream.class);
    try
    {
      if(response.getStatusType() == Response.Status.NOT_MODIFIED)
      {
        return null;
      }
      else if(response.getStatusType() == Response.Status.OK)
      {
        R resource = unmarshaller.unmarshal(entity, resourceDescriptor,
            resourceFactory);
        addMissingMetaData(response, resource);
        return resource;
      }
      else
      {
        throw createErrorResponseException(response, entity);
      }
    }
    finally
    {
      try {
        if (entity != null) {
          entity.close();
        }
      } catch (IOException e) {
        // Lets just log this and ignore.
        Debug.debugException(e);
      }
    }
  }

  /**
   * Retrieves all resource instances that match the provided filter.
   *
   * @param filter The filter that should be used.
   * @return The resource instances that match the provided filter.
   * @throws SCIMException If an error occurs.
   */
  public Resources<R> query(final String filter)
      throws SCIMException
  {
    return query(filter, null, null, null);
  }

  /**
   * Retrieves all resource instances that match the provided filter.
   * Matching resources are returned sorted according to the provided
   * SortParameters. PageParameters maybe used to specify the range of
   * resource instances that are returned.
   *
   * @param filter The filter that should be used.
   * @param sortParameters The sort parameters that should be used.
   * @param pageParameters The page parameters that should be used.
   * @param requestedAttributes The attributes of the resource to retrieve.
   * @return The resource instances that match the provided filter.
   * @throws SCIMException If an error occurs.
   */
  public Resources<R> query(final String filter,
                            final SortParameters sortParameters,
                            final PageParameters pageParameters,
                            final String... requestedAttributes)
      throws SCIMException
  {
    URI uri =
        UriBuilder.fromUri(scimService.getBaseURL()).path(
            resourceDescriptor.getEndpoint()).build();
    org.apache.wink.client.Resource clientResource = client.resource(uri);
    clientResource.accept(acceptType);
    clientResource.contentType(contentType);
    addAttributesQuery(clientResource, requestedAttributes);
    if(filter != null)
    {
      clientResource.queryParam("filter", filter);
    }
    if(sortParameters != null)
    {
      clientResource.queryParam("sortBy",
          sortParameters.getSortBy().toString());
      if(!sortParameters.isAscendingOrder())
      {
        clientResource.queryParam("sortOrder", sortParameters.getSortOrder());
      }
    }
    if(pageParameters != null)
    {
      clientResource.queryParam("startIndex",
          String.valueOf(pageParameters.getStartIndex()));
      if (pageParameters.getCount() > 0)
      {
        clientResource.queryParam("count",
                                  String.valueOf(pageParameters.getCount()));
      }
    }

    ClientResponse response = clientResource.get();
    InputStream entity = response.getEntity(InputStream.class);
    try
    {
      if(response.getStatusType() == Response.Status.OK)
      {
        return unmarshaller.unmarshalResources(entity, resourceDescriptor,
            resourceFactory);
      }
      else
      {
        throw createErrorResponseException(response, entity);
      }
    }
    finally
    {
      try {
        if (entity != null) {
          entity.close();
        }
      } catch (IOException e) {
        // Lets just log this and ignore.
        Debug.debugException(e);
      }
    }
  }



  /**
   * Create the specified resource instance at the service provider.
   *
   * @param resource The resource to create.
   * @return The newly inserted resource returned by the service provider.
   * @throws SCIMException If an error occurs.
   */
  public R create(final R resource) throws SCIMException
  {
    return create(resource, null);
  }

  /**
   * Create the specified resource instance at the service provider and return
   * only the specified attributes from the newly inserted resource.
   *
   * @param resource The resource to create.
   * @param requestedAttributes The attributes of the newly inserted resource
   *                            to retrieve.
   * @return The newly inserted resource returned by the service provider.
   * @throws SCIMException If an error occurs.
   */
  public R create(final R resource,
                  final String... requestedAttributes)
      throws SCIMException
  {

    URI uri =
        UriBuilder.fromUri(scimService.getBaseURL()).path(
            resourceDescriptor.getEndpoint()).build();
    org.apache.wink.client.Resource clientResource = client.resource(uri);
    clientResource.accept(acceptType);
    clientResource.contentType(contentType);
    addAttributesQuery(clientResource, requestedAttributes);

    StreamingOutput output = new StreamingOutput() {
      public void write(final OutputStream outputStream)
          throws IOException, WebApplicationException {
        try {
          marshaller.marshal(resource, outputStream);
        } catch (Exception e) {
          throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }
      }
    };

    ClientResponse response = clientResource.post(output);
    InputStream entity = response.getEntity(InputStream.class);
    try
    {
      if(response.getStatusType() == Response.Status.CREATED)
      {
        R postedResource = unmarshaller.unmarshal(entity, resourceDescriptor,
            resourceFactory);
        addMissingMetaData(response, postedResource);
        return postedResource;
      }
      else
      {
        throw createErrorResponseException(response, entity);
      }
    }
    finally
    {
      try {
        if (entity != null) {
          entity.close();
        }
      } catch (IOException e) {
        // Lets just log this and ignore.
        Debug.debugException(e);
      }
    }
  }

  /**
   * Update the existing resource with the one provided.
   *
   * @param resource The modified resource to be updated.
   * @return The updated resource returned by the service provider.
   * @throws SCIMException If an error occurs.
   */
  public R update(final R resource)
      throws SCIMException
  {
    return update(resource, null, null);
  }

  /**
   * Update the existing resource with the one provided. This update is
   * conditional upon the provided entity tag matching the tag from the
   * current resource. If (and only if) they match, the update will be
   * performed.
   *
   * @param resource The modified resource to be updated.
   * @param etag The entity tag value that is the expected value for the target
   *             resource. A value of <code>null</code> will not set an
   *             etag precondition and a value of "*" will perform an
   *             unconditional update.
   * @param requestedAttributes The attributes of updated resource
   *                            to return.
   * @return The updated resource returned by the service provider.
   * @throws SCIMException If an error occurs.
   */
  public R update(final R resource, final String etag,
                  final String... requestedAttributes)
      throws SCIMException
  {
    String id = resource.getId();
    if(id == null)
    {
      throw new InvalidResourceException("Resource must have a valid ID");
    }
    URI uri =
        UriBuilder.fromUri(scimService.getBaseURL()).path(
            resourceDescriptor.getEndpoint()).path(id).build();
    org.apache.wink.client.Resource clientResource = client.resource(uri);
    clientResource.accept(acceptType);
    clientResource.contentType(contentType);
    addAttributesQuery(clientResource, requestedAttributes);
    if(etag != null && !etag.isEmpty())
    {
      clientResource.header("If-Match", etag);
    }

    StreamingOutput output = new StreamingOutput() {
      public void write(final OutputStream outputStream)
          throws IOException, WebApplicationException {
        try {
          marshaller.marshal(resource, outputStream);
        } catch (Exception e) {
          throw new WebApplicationException(e, Response.Status.BAD_REQUEST);
        }
      }
    };

    ClientResponse response;
    if(overrides[0])
    {
      clientResource.header("X-HTTP-Method-Override", "PUT");
      response = clientResource.post(output);
    }
    else
    {
      response = clientResource.put(output);
    }

    InputStream entity = response.getEntity(InputStream.class);
    try
    {
      if(response.getStatusType() == Response.Status.OK)
      {
        R postedResource = unmarshaller.unmarshal(entity, resourceDescriptor,
            resourceFactory);
        addMissingMetaData(response, postedResource);
        return postedResource;
      }
      else
      {
        throw createErrorResponseException(response, entity);
      }
    }
    finally
    {
      try {
        if (entity != null) {
          entity.close();
        }
      } catch (IOException e) {
        // Lets just log this and ignore.
        Debug.debugException(e);
      }
    }
  }

  /**
   * Delete the resource instance specified by the provided ID.
   *
   * @param id The ID of the resource to delete.
   * @throws SCIMException If an error occurs.
   */
  public void delete(final String id)
      throws SCIMException
  {
    delete(id, null);
  }

  /**
   * Delete the resource instance specified by the provided ID. This delete is
   * conditional upon the provided entity tag matching the tag from the
   * current resource. If (and only if) they match, the delete will be
   * performed.
   *
   * @param id The ID of the resource to delete.
   * @param etag The entity tag value that is the expected value for the target
   *             resource. A value of <code>null</code> will not set an
   *             etag precondition and a value of "*" will perform an
   *             unconditional delete.
   * @throws SCIMException If an error occurs.
   */
  public void delete(final String id, final String etag)
      throws SCIMException
  {
    URI uri =
        UriBuilder.fromUri(scimService.getBaseURL()).path(
            resourceDescriptor.getEndpoint()).path(id).build();
    org.apache.wink.client.Resource clientResource = client.resource(uri);
    clientResource.accept(acceptType);
    clientResource.contentType(contentType);
    if(etag != null && !etag.isEmpty())
    {
      clientResource.header("If-Match", etag);
    }

    ClientResponse response;

    if(overrides[2])
    {
      clientResource.header("X-HTTP-Method-Override", "DELETE");
      response = clientResource.post(null);
    }
    else
    {
      response = clientResource.delete();
    }

    InputStream entity = response.getEntity(InputStream.class);
    try
    {
      if(response.getStatusType() != Response.Status.OK)
      {
        throw createErrorResponseException(response, entity);
      }
    }
    finally
    {
      try {
        if (entity != null) {
          entity.close();
        }
      } catch (IOException e) {
        // Lets just log this and ignore.
        Debug.debugException(e);
      }
    }
  }

  /**
   * Add the attributes query parameter to the client resource request.
   *
   * @param clientResource The Wink client resource.
   * @param requestedAttributes The SCIM attributes to request.
   */
  private void addAttributesQuery(
      final org.apache.wink.client.Resource clientResource,
      final String... requestedAttributes)
  {
    if(requestedAttributes != null && requestedAttributes.length > 0)
    {
      StringBuilder stringBuilder = new StringBuilder();
      for(int i = 0; i < requestedAttributes.length; i++)
      {
        stringBuilder.append(requestedAttributes[i]);
        if(i < requestedAttributes.length - 1)
        {
          stringBuilder.append(",");
        }
      }
      clientResource.queryParam("attributes", stringBuilder.toString());
    }
  }

  /**
   * Add meta values from the response header to the meta complex attribute
   * if they are missing.
   *
   * @param response The response from the service provider.
   * @param resource The return resource instance.
   */
  private void addMissingMetaData(final ClientResponse response,
                                  final R resource)
  {
    URI headerLocation = null;
    String headerEtag = null;
    List<String> values = response.getHeaders().get("Location");
    if(values != null && !values.isEmpty())
    {
      headerLocation = URI.create(values.get(0));
    }
    values = response.getHeaders().get("Etag");
    if(values != null && !values.isEmpty())
    {
      headerEtag = values.get(0);
    }
    Meta meta = resource.getMeta();
    if(meta == null)
    {
      meta = new Meta(null, null, null, null);
    }
    boolean modified = false;
    if(headerLocation != null && meta.getLocation() == null)
    {
      meta.setLocation(headerLocation);
      modified = true;
    }
    if(headerEtag != null && meta.getVersion() == null)
    {
      meta.setVersion(headerEtag);
      modified = true;
    }
    if(modified)
    {
      resource.setMeta(meta);
    }
  }



  /**
   * Returns a SCIM exception representing the error response.
   *
   * @param response  The client response.
   * @param entity    The response content.
   *
   * @return  The SCIM exception representing the error response.
   */
  private SCIMException createErrorResponseException(
      final ClientResponse response,
      final InputStream entity)
  {
    SCIMException scimException = null;

    if(entity != null)
    {
      try
      {
        scimException = unmarshaller.unmarshalError(entity);
      }
      catch (InvalidResourceException e)
      {
        // The response content could not be parsed as a SCIM error
        // response, which is the case if the response is a more general
        // HTTP error. It is better to just provide the HTTP response
        // details in this case.
        Debug.debugException(e);
      }
    }

    if(scimException == null)
    {
      scimException = SCIMException.createException(
          response.getStatusCode(), response.getMessage());
    }

    return scimException;
  }
}
