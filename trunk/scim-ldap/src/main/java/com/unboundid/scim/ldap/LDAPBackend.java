/*
 * Copyright 2011-2013 UnboundID Corp.
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

package com.unboundid.scim.ldap;

import com.unboundid.ldap.sdk.AddRequest;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.DeleteRequest;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ModifyDNRequest;
import com.unboundid.ldap.sdk.ModifyRequest;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.controls.PostReadRequestControl;
import com.unboundid.ldap.sdk.controls.PostReadResponseControl;
import com.unboundid.ldap.sdk.controls.ServerSideSortRequestControl;
import com.unboundid.ldap.sdk.controls.SortKey;
import com.unboundid.ldap.sdk.controls.VirtualListViewRequestControl;
import com.unboundid.ldap.sdk.controls.VirtualListViewResponseControl;
import com.unboundid.scim.data.AttributeValueResolver;
import com.unboundid.scim.data.Meta;
import com.unboundid.scim.data.BaseResource;
import com.unboundid.scim.schema.AttributeDescriptor;
import com.unboundid.scim.schema.CoreSchema;
import com.unboundid.scim.schema.ResourceDescriptor;
import com.unboundid.scim.sdk.Debug;
import com.unboundid.scim.sdk.DebugType;
import com.unboundid.scim.sdk.InvalidResourceException;
import com.unboundid.scim.sdk.PatchResourceRequest;
import com.unboundid.scim.sdk.ResourceNotFoundException;
import com.unboundid.scim.sdk.Resources;
import com.unboundid.scim.sdk.SCIMAttributeValue;
import com.unboundid.scim.sdk.SCIMBackend;
import com.unboundid.scim.sdk.SCIMConstants;
import com.unboundid.scim.sdk.SCIMException;
import com.unboundid.scim.sdk.SCIMObject;
import com.unboundid.scim.sdk.SCIMQueryAttributes;
import com.unboundid.scim.sdk.SCIMRequest;
import com.unboundid.scim.sdk.SCIMFilter;
import com.unboundid.scim.sdk.SCIMFilterType;
import com.unboundid.scim.sdk.AttributePath;
import com.unboundid.scim.sdk.SCIMAttribute;
import com.unboundid.scim.sdk.PageParameters;
import com.unboundid.scim.sdk.ServerErrorException;
import com.unboundid.scim.sdk.SortParameters;
import com.unboundid.scim.sdk.GetResourceRequest;
import com.unboundid.scim.sdk.GetResourcesRequest;
import com.unboundid.scim.sdk.PostResourceRequest;
import com.unboundid.scim.sdk.DeleteResourceRequest;
import com.unboundid.scim.sdk.PutResourceRequest;
import com.unboundid.scim.sdk.UnsupportedOperationException;
import com.unboundid.util.StaticUtils;
import com.unboundid.util.Validator;

import javax.ws.rs.core.UriBuilder;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import static com.unboundid.scim.sdk.SCIMConstants.SCHEMA_URI_CORE;



/**
 * This abstract class is a base class for implementations of the SCIM server
 * backend API that use an LDAP-based resource storage repository.
 */
public abstract class LDAPBackend
    extends SCIMBackend
{
  /**
   * The default set of timestamp attributes that we need to ask for when
   * making requests to the underlying LDAP server.
   */
  private static final Set<String> DEFAULT_LASTMOD_ATTRS;

  /**
   * The resource mappers configured for SCIM resource end-points.
   */
  private volatile Map<ResourceDescriptor, ResourceMapper> resourceMappers;

  static
  {
    HashSet<String> attrs = new HashSet<String>(2);
    attrs.add("createTimestamp");
    attrs.add("modifyTimestamp");
    DEFAULT_LASTMOD_ATTRS = Collections.unmodifiableSet(attrs);
  }



  /**
   * Create a new instance of an LDAP backend.
   *
   * @param  resourceMappers  The resource mappers configured for SCIM resource
   *                          end-points.
   */
  public LDAPBackend(
      final Map<ResourceDescriptor, ResourceMapper> resourceMappers)
  {
    this.resourceMappers = resourceMappers;
  }



  /**
   * Specifies the resource mappers configured for SCIM resource end-points.
   * @param resourceMappers  The resource mappers configured for SCIM resource
   *                         end-points.
   */
  public void setResourceMappers(
      final Map<ResourceDescriptor, ResourceMapper> resourceMappers)
  {
    this.resourceMappers = resourceMappers;
  }



  /**
   * Retrieve an LDAP interface that may be used to interact with the LDAP
   * server.
   *
   *
   * @param userID  The authenticated user ID for the request being processed.
   *
   * @return  An LDAP interface that may be used to interact with the LDAP
   *          server.
   *
   * @throws SCIMException  If there was a problem retrieving an LDAP interface.
   */
  protected abstract LDAPRequestInterface getLDAPRequestInterface(
      final String userID)
      throws SCIMException;



  /**
   * Get the names of the create-time and modify-time attributes to request
   * when searching the directory server. Typically these will be
   * 'createTimestamp' and 'modifyTimestamp', but this can be overridden by
   * subclasses.
   *
   * @return the set of last-mod attributes to request when performing
   *         operations which return an entry from the directory server.
   */
  protected Set<String> getLastModAttributes()
  {
    return DEFAULT_LASTMOD_ATTRS;
  }



  /**
   * Retrieve the resource mapper for the provided resource descriptor.
   *
   * @param resourceDescriptor The ResourceDescriptor for which the resource
   *                           mapper is requested.
   *
   * @return  The resource mapper for the provided resource descriptor.
   *
   * @throws  SCIMException  If there is no such resource mapper.
   */
  ResourceMapper getResourceMapper(final ResourceDescriptor resourceDescriptor)
      throws SCIMException
  {
    final ResourceMapper mapper = resourceMappers.get(resourceDescriptor);
    if (mapper == null)
    {
      throw new ServerErrorException(
          "No resource mapper found for resource '" +
          resourceDescriptor.getName() + "'");
    }

    return mapper;
  }



  @Override
  public BaseResource getResource(
      final GetResourceRequest request) throws SCIMException
  {
    final ResourceMapper mapper =
        getResourceMapper(request.getResourceDescriptor());

    final Set<String> requestAttributeSet = new HashSet<String>();
    requestAttributeSet.addAll(
        mapper.toLDAPAttributeTypes(request.getAttributes()));
    requestAttributeSet.addAll(getLastModAttributes());
    requestAttributeSet.add("objectclass");

    final String[] requestAttributes = new String[requestAttributeSet.size()];
    requestAttributeSet.toArray(requestAttributes);

    final LDAPRequestInterface ldapInterface =
        getLDAPRequestInterface(request.getAuthenticatedUserID());

    final Entry entry =
        mapper.getEntry(ldapInterface, request.getResourceID(),
                        requestAttributes);
    final BaseResource resource =
        new BaseResource(request.getResourceDescriptor());

    setIdAndMetaAttributes(mapper, resource, request, entry,
                           request.getAttributes());

    final List<SCIMAttribute> attributes = mapper.toSCIMAttributes(
        entry, request.getAttributes(), ldapInterface);
    for (final SCIMAttribute a : attributes)
    {
      Validator.ensureTrue(resource.getScimObject().addAttribute(a));
    }

    return resource;
  }



  @Override
  public Resources<?> getResources(final GetResourcesRequest request)
      throws SCIMException
  {
    final ResourceMapper resourceMapper =
        getResourceMapper(request.getResourceDescriptor());
    if (resourceMapper == null || !resourceMapper.supportsQuery())
    {
      throw new UnsupportedOperationException(
          "The requested operation is not supported on resource end-point '" +
              request.getResourceDescriptor().getEndpoint() + "'");
    }

    try
    {
      final SCIMFilter scimFilter = request.getFilter();

      final Set<String> requestAttributeSet =
          resourceMapper.toLDAPAttributeTypes(request.getAttributes());
      requestAttributeSet.addAll(getLastModAttributes());
      requestAttributeSet.add("objectclass");

      final int maxResults = getConfig().getMaxResults();
      final LDAPRequestInterface ldapInterface =
          getLDAPRequestInterface(request.getAuthenticatedUserID());
      final ResourceSearchResultListener resultListener =
          new ResourceSearchResultListener(this, request, ldapInterface,
                                           maxResults);
      SearchRequest searchRequest = null;
      if (scimFilter != null)
      {
        if (resourceMapper.idMapsToDn())
        {
          if (scimFilter.getFilterType() == SCIMFilterType.EQUALITY)
          {
            final AttributePath path = scimFilter.getFilterAttribute();
            if (path.getAttributeSchema().equalsIgnoreCase(SCHEMA_URI_CORE) &&
                path.getAttributeName().equalsIgnoreCase("id"))
            {
              final String[] requestAttributes =
                  new String[requestAttributeSet.size()];
              requestAttributeSet.toArray(requestAttributes);

              searchRequest =
                  new SearchRequest(resultListener, scimFilter.getFilterValue(),
                                    SearchScope.BASE,
                                    Filter.createPresenceFilter("objectclass"),
                                    requestAttributes);
            }
          }
        }
      }

      Set<DN> searchBaseDNs;
      if (request.getBaseID() != null)
      {
        Entry baseEntry = resourceMapper.getEntry(
                ldapInterface, request.getBaseID(), "1.1");

        //Make sure the requested base ID maps to an entry that is within the
        //configured base DN(s) for this resource type.
        boolean isAllowed = false;
        if (baseEntry != null)
        {
          for (DN baseDN : resourceMapper.getSearchBaseDNs())
          {
            if (baseDN.isAncestorOf(baseEntry.getParsedDN(), true))
            {
              isAllowed = true;
              break;
            }
          }
        }

        if (!isAllowed)
        {
          throw new InvalidResourceException("The specified base-id does not " +
                      "exist under any of the configured branches of the DIT.");
        }
        else
        {
          searchBaseDNs = Collections.singleton(baseEntry.getParsedDN());
        }
      }
      else
      {
        searchBaseDNs = resourceMapper.getSearchBaseDNs();
      }

      SearchScope searchScope;
      if (request.getSearchScope() != null)
      {
        if (SearchScope.BASE.getName().equalsIgnoreCase(
                    request.getSearchScope()))
        {
          searchScope = SearchScope.BASE;
        }
        else if (SearchScope.ONE.getName().equalsIgnoreCase(
                    request.getSearchScope()))
        {
          searchScope = SearchScope.ONE;
        }
        else if (SearchScope.SUB.getName().equalsIgnoreCase(
                    request.getSearchScope()))
        {
          searchScope = SearchScope.SUB;
        }
        else if ("subordinate".equalsIgnoreCase(request.getSearchScope()))
        {
          searchScope = SearchScope.SUBORDINATE_SUBTREE;
        }
        else
        {
          throw new InvalidResourceException("Search scope '" +
                  request.getSearchScope() + "' is not supported.");
        }
      }
      else
      {
        searchScope = SearchScope.SUB;
      }

      SearchResult searchResult = null;
      int startIndex = 1;
      int totalToReturn = maxResults;

      for (DN baseDN : searchBaseDNs)
      {
        if (searchRequest == null)
        {
          final Filter filter;
          try
          {
            // Map the SCIM filter to an LDAP filter.
            filter = resourceMapper.toLDAPFilter(scimFilter);
          }
          catch(InvalidResourceException ire)
          {
            throw new InvalidResourceException("Invalid filter: " +
                    ire.getLocalizedMessage(), ire);
          }

          if(filter == null)
          {
            // Match nothing... Just return an empty resources set.
            List<BaseResource> emptyList = Collections.emptyList();
            return new Resources<BaseResource>(emptyList);
          }

          // The LDAP filter results will still need to be filtered using the
          // SCIM filter, so we need to request all the filter attributes.
          addFilterAttributes(requestAttributeSet, filter);

          final String[] requestAttributes =
                  new String[requestAttributeSet.size()];
          requestAttributeSet.toArray(requestAttributes);

          searchRequest = new SearchRequest(resultListener, baseDN.toString(),
                                    searchScope, filter, requestAttributes);
        }

        final SortParameters sortParameters = request.getSortParameters();
        if (sortParameters != null)
        {
          try
          {
            Control control = resourceMapper.toLDAPSortControl(sortParameters);
            if(control != null)
            {
              searchRequest.addControl(control);
            }
          }
          catch(InvalidResourceException ire)
          {
            throw new InvalidResourceException("Invalid sort parameters: " +
                    ire.getLocalizedMessage(), ire);
          }
        }

        // Use the VLV control to perform pagination.
        final PageParameters pageParameters = request.getPageParameters();
        int numLeftToReturn = totalToReturn - resultListener.getTotalResults();
        if (pageParameters != null)
        {
          if (pageParameters.getCount() > 0)
          {
            totalToReturn = pageParameters.getCount();
            numLeftToReturn = Math.min(totalToReturn, maxResults) -
                      resultListener.getTotalResults();
          }

          //We cannot set a size limit when using the VLV control; it will
          //handle that internally.
          searchRequest.setSizeLimit(0);

          startIndex = (int) pageParameters.getStartIndex();
          searchRequest.addControl(
                  new VirtualListViewRequestControl(
                          startIndex, 0, numLeftToReturn-1, 0, null, true));

          // VLV requires a sort control.
          if (!searchRequest.hasControl(
                  ServerSideSortRequestControl.SERVER_SIDE_SORT_REQUEST_OID))
          {
            searchRequest.addControl(
                  new ServerSideSortRequestControl(new SortKey("uid"))); // TODO
          }
        }
        else
        {
          searchRequest.setSizeLimit(maxResults);
        }

        // Invoke the search operation.
        try
        {
          searchResult = ldapInterface.search(searchRequest);
        }
        catch (LDAPSearchException e)
        {
          if(e.getResultCode().equals(ResultCode.SIZE_LIMIT_EXCEEDED))
          {
            searchResult = e.getSearchResult();
          }
          else
          {
            throw e;
          }
        }

        if (searchRequest.getScope() == SearchScope.BASE ||
                resultListener.getTotalResults() >= totalToReturn)
        {
          break;
        }
        else
        {
          searchRequest = null;
        }
      }

      // Prepare the response.
      List<BaseResource> scimObjects = resultListener.getResources();
      int toIdx = Math.min(scimObjects.size(), totalToReturn);
      scimObjects = scimObjects.subList(0, toIdx);

      final VirtualListViewResponseControl vlvResponseControl =
                  getVLVResponseControl(searchResult);

      if (vlvResponseControl != null)
      {
        return new Resources<BaseResource>(scimObjects,
                      vlvResponseControl.getContentCount(), startIndex);
      }
      else
      {
        return new Resources<BaseResource>(scimObjects,
                      resultListener.getTotalResults(), 1);
      }
    }
    catch (LDAPException e)
    {
      Debug.debugException(e);
      throw ResourceMapper.toSCIMException(e);
    }
  }



  /**
   * {@inheritDoc}
   */
  @Override
  public BaseResource postResource(
      final PostResourceRequest request) throws SCIMException
  {
    if(getConfig().isCheckSchema())
    {
      // Make sure the resource doesn't violate the schema
      request.getResourceObject().checkSchema(
          request.getResourceDescriptor(), false);
    }

    final ResourceMapper mapper =
        getResourceMapper(request.getResourceDescriptor());

    final Set<String> requestAttributeSet = new HashSet<String>();
    requestAttributeSet.addAll(
        mapper.toLDAPAttributeTypes(request.getAttributes()));
    requestAttributeSet.addAll(getLastModAttributes());
    requestAttributeSet.add("objectclass");

    final String[] requestAttributes = new String[requestAttributeSet.size()];
    requestAttributeSet.toArray(requestAttributes);

    try
    {
      if (!mapper.supportsCreate())
      {
        throw new UnsupportedOperationException(
            "The '" + request.getResourceDescriptor().getName() +
            "' resource definition does not support creation of resources");
      }

      final LDAPRequestInterface ldapInterface =
          getLDAPRequestInterface(request.getAuthenticatedUserID());
      final Entry entry =
          mapper.toLDAPEntry(request.getResourceObject(), ldapInterface);

      final AddRequest addRequest = new AddRequest(entry);
      addRequest.addControl(
          new PostReadRequestControl(requestAttributes));

      final LDAPResult addResult = ldapInterface.add(addRequest);

      final PostReadResponseControl c = getPostReadResponseControl(addResult);
      Entry addedEntry = entry;
      if (c != null)
      {
        addedEntry = c.getEntry();

        // Work around issue DS-5918.
        if (addedEntry.hasAttribute("entryUUID"))
        {
          final SearchRequest r =
              new SearchRequest(entry.getDN(),
                                SearchScope.BASE,
                                Filter.createPresenceFilter("objectclass"),
                                requestAttributes);
          r.setSizeLimit(1);
          final Entry actualEntry = ldapInterface.searchForEntry(r);
          if (actualEntry != null)
          {
            addedEntry = actualEntry;
          }
        }
      }

      final BaseResource resource =
          new BaseResource(request.getResourceDescriptor());

      setIdAndMetaAttributes(mapper, resource, request, addedEntry,
                             request.getAttributes());

      final List<SCIMAttribute> scimAttributes = mapper.toSCIMAttributes(
          addedEntry, request.getAttributes(), ldapInterface);
      for (final SCIMAttribute a : scimAttributes)
      {
        Validator.ensureTrue(resource.getScimObject().addAttribute(a));
      }

      return resource;
    }
    catch (LDAPException e)
    {
      Debug.debugException(e);
      throw ResourceMapper.toSCIMException(e);
    }
  }



  /**
   * {@inheritDoc}
   */
  @Override
  public void deleteResource(final DeleteResourceRequest request)
      throws SCIMException
  {
    final ResourceMapper mapper =
        getResourceMapper(request.getResourceDescriptor());

    try
    {
      final LDAPRequestInterface ldapInterface =
          getLDAPRequestInterface(request.getAuthenticatedUserID());

      final Entry entry =
          mapper.getEntry(ldapInterface, request.getResourceID());
      final DeleteRequest deleteRequest = new DeleteRequest(entry.getDN());
      final LDAPResult result = ldapInterface.delete(deleteRequest);

      if (!result.getResultCode().equals(ResultCode.SUCCESS))
      {
        throw new LDAPException(result.getResultCode());
      }
    }
    catch (LDAPException e)
    {
      Debug.debugException(e);
      if (e.getResultCode().equals(ResultCode.NO_SUCH_OBJECT))
      {
        throw new ResourceNotFoundException(
            "Resource " + request.getResourceID() + " not found");
      }
      throw ResourceMapper.toSCIMException(e);
    }
  }



  /**
   * {@inheritDoc}
   */
  @Override
  public BaseResource putResource(final PutResourceRequest request)
      throws SCIMException
  {
    if(getConfig().isCheckSchema())
    {
      // Make sure the resource doesn't violate the schema
      request.getResourceObject().checkSchema(
          request.getResourceDescriptor(), false);
    }

    final ResourceMapper mapper =
        getResourceMapper(request.getResourceDescriptor());

    // Retrieve all modifiable mapped attributes to get the current state of
    // the resource.
    final Set<String> mappedAttributeSet =
        mapper.getModifiableLDAPAttributeTypes(request.getResourceObject());
    final String[] mappedAttributes = new String[mappedAttributeSet.size()];
    mappedAttributeSet.toArray(mappedAttributes);

    final String resourceID = request.getResourceID();
    final List<Modification> mods = new ArrayList<Modification>();
    Entry modifiedEntry = null;
    try
    {
      final LDAPRequestInterface ldapInterface =
          getLDAPRequestInterface(request.getAuthenticatedUserID());
      final Entry currentEntry =
          mapper.getEntry(ldapInterface, resourceID, mappedAttributes);

      mods.addAll(mapper.toLDAPModificationsForPut(currentEntry,
          request.getResourceObject(), mappedAttributes, ldapInterface));

      final Set<String> requestAttributeSet = new HashSet<String>();
      requestAttributeSet.addAll(
          mapper.toLDAPAttributeTypes(request.getAttributes()));
      requestAttributeSet.addAll(getLastModAttributes());
      requestAttributeSet.add("objectclass");

      final String[] requestAttributes =
          new String[requestAttributeSet.size()];
      requestAttributeSet.toArray(requestAttributes);

      if(!mods.isEmpty())
      {
        // Look for any modifications that will affect the mapped entry's RDN
        // and split them up.
        modifiedEntry = currentEntry.duplicate();
        ListIterator<Modification> iterator = mods.listIterator();
        List<String> rdnAttrNames = new ArrayList<String>(1);
        List<String> rdnAttrValues = new ArrayList<String>(1);

        while(iterator.hasNext())
        {
          Modification mod = iterator.next();
          if((mod.getModificationType() == ModificationType.INCREMENT ||
              mod.getModificationType() == ModificationType.REPLACE) &&
              currentEntry.getRDN().hasAttribute(mod.getAttributeName()))
          {
            if (mod.getValues().length != 1)
            {
              throw new InvalidResourceException(
                         "The '" + mod.getAttributeName() +
                         "' attribute must contain exactly one value because " +
                         "it is an RDN attribute.");
            }

            iterator.remove();

            rdnAttrNames.add(mod.getAttributeName());
            rdnAttrValues.add(mod.getValues()[0]);

            // The modification will affect the RDN so we need to first apply
            // the mods in memory and reconstruct the DN. We will set the DN to
            // null first so Entry.applyModifications wouldn't throw any
            // exceptions about affecting the RDN.
            DN parentDN = modifiedEntry.getParentDN();
            modifiedEntry.setDN("");
            modifiedEntry =
                Entry.applyModifications(modifiedEntry, true, mod);

            DN newDN = new DN(new RDN(
                     rdnAttrNames.toArray(new String[rdnAttrNames.size()]),
                     rdnAttrValues.toArray(new String[rdnAttrValues.size()])),
                       parentDN);

            modifiedEntry.setDN(newDN);
          }
        }

        PostReadResponseControl c = null;
        if(!modifiedEntry.getParsedDN().equals(currentEntry.getParsedDN()))
        {
          ModifyDNRequest modifyDNRequest =
              new ModifyDNRequest(currentEntry.getDN(),
                  modifiedEntry.getRDN().toString(), true);

          // If there are no other mods left, we need to include the
          // PostReadRequestControl now since we won't be performing a modify
          // operation later.
          if(mods.isEmpty())
          {
            modifyDNRequest.addControl(
                new PostReadRequestControl(requestAttributes));
          }
          final LDAPResult modifyDNResult =
              ldapInterface.modifyDN(modifyDNRequest);
          if(mods.isEmpty())
          {
            c = getPostReadResponseControl(modifyDNResult);
          }
        }

        if(!mods.isEmpty())
        {
          final ModifyRequest modifyRequest =
              new ModifyRequest(modifiedEntry.getDN(), mods);
          modifyRequest.addControl(
                new PostReadRequestControl(requestAttributes));
          final LDAPResult modifyResult = ldapInterface.modify(modifyRequest);
          c = getPostReadResponseControl(modifyResult);
        }

        if (c != null)
        {
          modifiedEntry = c.getEntry();

          // Work around issue DS-5918.
          if (modifiedEntry.hasAttribute("entryUUID"))
          {
            final SearchRequest r =
                new SearchRequest(modifiedEntry.getDN(),
                                  SearchScope.BASE,
                                  Filter.createPresenceFilter("objectclass"),
                                  requestAttributes);
            r.setSizeLimit(1);
            final Entry actualEntry = ldapInterface.searchForEntry(r);
            if (actualEntry != null)
            {
              modifiedEntry = actualEntry;
            }
          }
        }
        else
        {
          modifiedEntry =
              mapper.getEntry(ldapInterface, resourceID, requestAttributes);
        }
      }
      else
      {
        // No modifications necessary (the mod set is empty).
        // Fetch the entry again, this time with the required return attributes.
        modifiedEntry =
            mapper.getEntry(ldapInterface, resourceID, requestAttributes);
      }

      final BaseResource resource =
                  new BaseResource(request.getResourceDescriptor());
      setIdAndMetaAttributes(mapper, resource, request, modifiedEntry,
                             request.getAttributes());

      final List<SCIMAttribute> scimAttributes = mapper.toSCIMAttributes(
        modifiedEntry, request.getAttributes(), ldapInterface);

      for (final SCIMAttribute a : scimAttributes)
      {
        Validator.ensureTrue(resource.getScimObject().addAttribute(a));
      }

      return resource;
    }
    catch (LDAPException e)
    {
      Debug.debugException(e);
      throw ResourceMapper.toSCIMException(e);
    }
  }



  /**
   * {@inheritDoc}
   */
  @Override
  public BaseResource patchResource(final PatchResourceRequest request)
          throws SCIMException
  {
    final ResourceMapper mapper =
            getResourceMapper(request.getResourceDescriptor());

    // Retrieve all modifiable mapped attributes to get the current state of
    // the resource.
    final Set<String> mappedAttributeSet =
          mapper.getModifiableLDAPAttributeTypes(request.getResourceObject());
    final String[] mappedAttributes = new String[mappedAttributeSet.size()];
    mappedAttributeSet.toArray(mappedAttributes);

    final String resourceID = request.getResourceID();
    final List<Modification> mods = new ArrayList<Modification>();
    Entry modifiedEntry = null;
    try
    {
      final LDAPRequestInterface ldapInterface =
              getLDAPRequestInterface(request.getAuthenticatedUserID());
      final Entry currentEntry =
              mapper.getEntry(ldapInterface, resourceID, mappedAttributes);

      mods.addAll(mapper.toLDAPModificationsForPatch(currentEntry,
              request.getResourceObject(), ldapInterface));

      final Set<String> requestAttributeSet = new HashSet<String>();
      requestAttributeSet.addAll(
            mapper.toLDAPAttributeTypes(request.getAttributes()));
      requestAttributeSet.addAll(getLastModAttributes());
      requestAttributeSet.add("objectclass");

      String[] requestAttributes = new String[requestAttributeSet.size()];
      requestAttributeSet.toArray(requestAttributes);

      if(!mods.isEmpty())
      {
        if(getConfig().isCheckSchema())
        {
          // Make sure the patch wouldn't cause any schema violations.
          checkSchemaForPatch(request, currentEntry, mapper, ldapInterface);
        }

        // Look for any modifications that will affect the mapped entry's RDN
        // and split them up.
        modifiedEntry = currentEntry.duplicate();
        ListIterator<Modification> iterator = mods.listIterator();
        List<String> rdnAttrNames = new ArrayList<String>(1);
        List<String> rdnAttrValues = new ArrayList<String>(1);

        while(iterator.hasNext())
        {
          Modification mod = iterator.next();
          if((mod.getModificationType() == ModificationType.INCREMENT ||
                  mod.getModificationType() == ModificationType.REPLACE) &&
                  currentEntry.getRDN().hasAttribute(mod.getAttributeName()))
          {
            if (mod.getValues().length != 1)
            {
              throw new InvalidResourceException(
                         "The '" + mod.getAttributeName() +
                         "' attribute must contain exactly one value because " +
                         "it is an RDN attribute.");
            }

            iterator.remove();

            rdnAttrNames.add(mod.getAttributeName());
            rdnAttrValues.add(mod.getValues()[0]);

            // The modification will affect the RDN so we need to first apply
            // the mods in memory and reconstruct the DN. We will set the DN to
            // null first so Entry.applyModifications wouldn't throw any
            // exceptions about affecting the RDN.
            DN parentDN = modifiedEntry.getParentDN();
            modifiedEntry.setDN("");
            modifiedEntry =
                    Entry.applyModifications(modifiedEntry, true, mod);

            DN newDN = new DN(new RDN(
                    rdnAttrNames.toArray(new String[rdnAttrNames.size()]),
                    rdnAttrValues.toArray(new String[rdnAttrValues.size()])),
                    parentDN);

            modifiedEntry.setDN(newDN);
          }
        }

        if (Debug.debugEnabled())
        {
          Debug.debug(Level.FINE, DebugType.OTHER,
                  "Patching resource, mods=" + mods);
        }

        PostReadResponseControl c = null;
        if(!modifiedEntry.getParsedDN().equals(currentEntry.getParsedDN()))
        {
          ModifyDNRequest modifyDNRequest =
                  new ModifyDNRequest(currentEntry.getDN(),
                          modifiedEntry.getRDN().toString(), true);

          // If there are no other mods left AND we need to return the resource,
          // then we need to include the PostReadRequestControl now since we
          // won't be performing a modify operation later.
          if(mods.isEmpty())
          {
            modifyDNRequest.addControl(
                    new PostReadRequestControl(requestAttributes));
          }
          final LDAPResult modifyDNResult =
                  ldapInterface.modifyDN(modifyDNRequest);
          if(mods.isEmpty())
          {
            c = getPostReadResponseControl(modifyDNResult);
          }
        }

        if(!mods.isEmpty())
        {
          final ModifyRequest modifyRequest =
                  new ModifyRequest(modifiedEntry.getDN(), mods);
          modifyRequest.addControl(
                new PostReadRequestControl(requestAttributes));
          final LDAPResult modifyResult = ldapInterface.modify(modifyRequest);
          c = getPostReadResponseControl(modifyResult);
        }

        if (c != null)
        {
          modifiedEntry = c.getEntry();

          // Work around issue DS-5918.
          if (modifiedEntry.hasAttribute("entryUUID"))
          {
            final SearchRequest r =
                    new SearchRequest(modifiedEntry.getDN(),
                            SearchScope.BASE,
                            Filter.createPresenceFilter("objectclass"),
                            requestAttributes);
            r.setSizeLimit(1);
            final Entry actualEntry = ldapInterface.searchForEntry(r);
            if (actualEntry != null)
            {
              modifiedEntry = actualEntry;
            }
          }
        }
        else
        {
          modifiedEntry =
              mapper.getEntry(ldapInterface, resourceID, requestAttributes);
        }
      }
      else
      {
        // No modifications were necessary (the mod set was empty).
        // Fetch the entry again, this time with the required return attributes.
        modifiedEntry =
                mapper.getEntry(ldapInterface, resourceID, requestAttributes);
      }

      final BaseResource resource =
              new BaseResource(request.getResourceDescriptor());
      setIdAndMetaAttributes(mapper, resource, request, modifiedEntry,
              request.getAttributes());

      //Only if the 'attributes' query parameter was specified do we need to
      //worry about returning anything other than the meta attributes.
      if (!request.getAttributes().allAttributesRequested())
      {
        final List<SCIMAttribute> scimAttributes = mapper.toSCIMAttributes(
              modifiedEntry, request.getAttributes(), ldapInterface);

        for (final SCIMAttribute a : scimAttributes)
        {
          Validator.ensureTrue(resource.getScimObject().addAttribute(a));
        }
      }

      if (Debug.debugEnabled())
      {
        Debug.debug(Level.FINE, DebugType.OTHER,
              "Returning resource from PATCH request: " + resource.toString());
      }

      return resource;
    }
    catch (LDAPException e)
    {
      Debug.debugException(e);
      throw ResourceMapper.toSCIMException(e);
    }
  }



  /**
   * Set the id and meta attributes in a SCIM object from the provided
   * information.
   *
   * @param resourceMapper   The resource mapper for the provided resource.
   * @param resource         The SCIM object whose id and meta attributes are
   *                         to be set.
   * @param request          The SCIM request.
   * @param entry            The LDAP entry from which the attribute values are
   *                         to be derived.
   * @param queryAttributes  The request query attributes, or {@code null} if
   *                         the attributes should not be pared down.
   *
   * @throws SCIMException  If an error occurs.
   */
  public static void setIdAndMetaAttributes(
      final ResourceMapper resourceMapper,
      final BaseResource resource,
      final SCIMRequest request,
      final Entry entry,
      final SCIMQueryAttributes queryAttributes)
      throws SCIMException
  {
    final String resourceID = resourceMapper.getIdFromEntry(entry);
    resource.setId(resourceID);

    Date createDate = null;
    Attribute createTimeAttr = entry.getAttribute("createTimestamp");
    if(createTimeAttr != null && createTimeAttr.hasValue())
    {
      try
      {
        createDate =
              StaticUtils.decodeGeneralizedTime(createTimeAttr.getValue());
      }
      catch(ParseException e)
      {
        Debug.debugException(e);
      }
    }
    else
    {
      createTimeAttr = entry.getAttribute("ds-create-time");

      if (createTimeAttr != null && createTimeAttr.hasValue())
      {
        try
        {
          createDate =
              expandCompactTimestamp(createTimeAttr.getValueByteArray());
        }
        catch (Exception e)
        {
          Debug.debugException(e);
        }
      }
    }

    Date modifyDate = null;
    Attribute modifyTimeAttr = entry.getAttribute("modifyTimestamp");
    if(modifyTimeAttr != null && modifyTimeAttr.hasValue())
    {
      try
      {
        modifyDate =
              StaticUtils.decodeGeneralizedTime(modifyTimeAttr.getValue());
      }
      catch(ParseException e)
      {
        Debug.debugException(e);
      }
    }
    else
    {
      modifyTimeAttr = entry.getAttribute("ds-update-time");

      if (modifyTimeAttr != null && modifyTimeAttr.hasValue())
      {
        try
        {
          modifyDate =
              expandCompactTimestamp(modifyTimeAttr.getValueByteArray());
        }
        catch (Exception e)
        {
          Debug.debugException(e);
        }
      }
    }

    final UriBuilder uriBuilder = UriBuilder.fromUri(request.getBaseURL());
    if (!request.getBaseURL().getPath().endsWith("v1/"))
    {
      uriBuilder.path("v1");
    }
    uriBuilder.path(resource.getResourceDescriptor().getEndpoint());
    uriBuilder.path(resourceID);

    resource.setMeta(new Meta(createDate, modifyDate,
        uriBuilder.build(), null));

    if (queryAttributes != null)
    {
      final SCIMAttribute meta =
          resource.getScimObject().getAttribute(
              SCHEMA_URI_CORE, "meta");
      resource.getScimObject().setAttribute(
          queryAttributes.pareAttribute(meta));
    }
  }



  /**
   * Retrieve a SASL Authentication ID from a HTTP Basic Authentication user ID.
   * We need this because the HTTP Authentication user ID can not include the
   * ':' character.
   *
   * @param userID  The HTTP user ID for which a SASL Authentication ID is
   *                required. It may be {@code null} if the request was not
   *                authenticated.
   *
   * @return  A SASL Authentication ID.
   */
  protected String getSASLAuthenticationID(final String userID)
  {
    if (userID == null)
    {
      return "";
    }

    // If the user ID can be parsed as a DN then prefix it with "dn:", otherwise
    // prefix it with "u:".
    try
    {
      final DN dn = new DN(userID);

      return "dn:" + dn.toString();
    }
    catch (LDAPException e)
    {
      Debug.debugException(Level.FINE, e);
      return "u:" + userID;
    }
  }



  /**
   * Extracts a virtual list view response control from the provided result.
   *
   * @param  result  The result from which to retrieve the virtual list view
   *                 response control.
   *
   * @return  The virtual list view response  control contained in the provided
   *          result, or {@code null} if the result did not contain a virtual
   *          list view response control.
   *
   * @throws  LDAPException  If a problem is encountered while attempting to
   *                         decode the virtual list view response  control
   *                         contained in the provided result.
   */
  private static VirtualListViewResponseControl getVLVResponseControl(
      final SearchResult result) throws LDAPException
  {
    if (result == null)
    {
      return null;
    }

    final Control c = result.getResponseControl(
        VirtualListViewResponseControl.VIRTUAL_LIST_VIEW_RESPONSE_OID);
    if (c == null)
    {
      return null;
    }

    if (c instanceof VirtualListViewResponseControl)
    {
      return (VirtualListViewResponseControl) c;
    }
    else
    {
      return new VirtualListViewResponseControl(c.getOID(), c.isCritical(),
          c.getValue());
    }
  }



  /**
   * Extracts a post-read response control from the provided result.
   *
   * @param  result  The result from which to retrieve the post-read response
   *                 control.
   *
   * @return  The post-read response control contained in the provided result,
   *          or {@code null} if the result did not contain a post-read response
   *          control.
   *
   * @throws  LDAPException  If a problem is encountered while attempting to
   *                         decode the post-read response control contained in
   *                         the provided result.
   */
  public static PostReadResponseControl getPostReadResponseControl(
      final LDAPResult result)
      throws LDAPException
  {
    final Control c = result.getResponseControl(
        PostReadResponseControl.POST_READ_RESPONSE_OID);
    if (c == null)
    {
      return null;
    }

    if (c instanceof PostReadResponseControl)
    {
      return (PostReadResponseControl) c;
    }
    else
    {
      return new PostReadResponseControl(c.getOID(), c.isCritical(),
          c.getValue());
    }
  }



  /**
   * Add all the attributes used in the specified filter to the provided
   * set of attributes.
   *
   * @param attributes  The set of attributes to which the filter attributes
   *                    should be added.
   *
   * @param filter      The filter whose attributes are of interest.
   */
  private static void addFilterAttributes(final Set<String> attributes,
                                          final Filter filter)
  {
    switch (filter.getFilterType())
    {
      case Filter.FILTER_TYPE_AND:
      case Filter.FILTER_TYPE_OR:
        for (final Filter f : filter.getComponents())
        {
          addFilterAttributes(attributes, f);
        }
        break;

      case Filter.FILTER_TYPE_NOT:
        addFilterAttributes(attributes, filter.getNOTComponent());
        break;

      case Filter.FILTER_TYPE_APPROXIMATE_MATCH:
      case Filter.FILTER_TYPE_EQUALITY:
      case Filter.FILTER_TYPE_GREATER_OR_EQUAL:
      case Filter.FILTER_TYPE_LESS_OR_EQUAL:
      case Filter.FILTER_TYPE_PRESENCE:
      case Filter.FILTER_TYPE_SUBSTRING:
        attributes.add(filter.getAttributeName());
        break;
    }
  }



  /**
   * This method expands the compact representation of the 'ds-create-time' and
   * 'ds-update-time' attributes from the Directory Server. These are stored in
   * a compact 8-byte format and decoded using the
   * ExpandTimestampVirtualAttributeProvider in the core server. This code is
   * modeled after that code, so consider updating this if that class changes.
   *
   * We would prefer to use the 'createTimestamp' and 'modifyTimestamp' virtual
   * attributes so as not to have to perform this conversion, but unfortunately
   * there is a bug with retrieving them using the PostReadResponseControl,
   * which is what we use when creating a new entry via SCIM.
   *
   * @param bytes the compact representation of the timestamp to expand. This
   *        must be exactly 8 bytes long.
   * @return a Date instance constructed from long represented by the bytes
   */
  private static Date expandCompactTimestamp(final byte[] bytes)
  {
    if(bytes.length != 8)
    {
      throw new IllegalArgumentException("The compact representation of the " +
              "timestamp was not 8 bytes");
    }
    long l = 0L;
    for (int i=0; i < 8; i++)
    {
      l <<= 8;
      l |= (bytes[i] & 0xFF);
    }
    return new Date(l);
  }



  /**
   * This method makes sure the PATCH request will not cause any schema
   * violations after it is applied to the resource.
   *
   * @param request The PATCH request.
   * @param currentEntry The current corresponding LDAP entry.
   * @param mapper The ResourceMapper in use.
   * @param ldapInterface The LDAPRequestInterface in use.
   * @throws SCIMException If any potential schema violations are found or if
   *                       there was an error during the determination.
   */
  public static void checkSchemaForPatch(
      final PatchResourceRequest request, final Entry currentEntry,
      final ResourceMapper mapper, final LDAPRequestInterface ldapInterface)
      throws SCIMException
  {
    SCIMObject patch = request.getResourceObject();
    ResourceDescriptor resourceDescriptor = request.getResourceDescriptor();
    SCIMAttribute meta = patch.getAttribute(SCIMConstants.SCHEMA_URI_CORE,
        CoreSchema.META_DESCRIPTOR.getName());

    if (meta != null)
    {
      SCIMAttributeValue value = meta.getValue();
      SCIMAttribute attributesAttr = value.getAttribute("attributes");
      if (attributesAttr != null)
      {
        // Make sure required attributes are not being removed
        for (SCIMAttributeValue val : attributesAttr.getValues())
        {
          AttributePath attributePath;
          if (val.isComplex())
          {
            attributePath = AttributePath.parse(
                val.getSubAttributeValue("value",
                    AttributeValueResolver.STRING_RESOLVER),
                    mapper.getDefaultSchemaURI());
          }
          else
          {
            attributePath = AttributePath.parse(val.getStringValue(),
                                                mapper.getDefaultSchemaURI());
          }

          AttributeDescriptor attributeToRemove =
              resourceDescriptor.getAttribute(
                  attributePath.getAttributeSchema(),
                  attributePath.getAttributeName());
          if(attributePath.getSubAttributeName() != null)
          {
            attributeToRemove = attributeToRemove.getSubAttribute(
                attributePath.getSubAttributeName());
          }

          if(attributeToRemove.isRequired())
          {
            throw new InvalidResourceException("Attribute '" +
                attributePath.toString() + "' may not be removed because it " +
                "is required");
          }
        }
      }
    }


    for (String schema : patch.getSchemas())
    {
      for (SCIMAttribute attr : patch.getAttributes(schema))
      {
        if(attr.getAttributeDescriptor().isRequired() &&
            attr.getAttributeDescriptor().isMultiValued())
        {
          int valuesDeleted = 0;
          // The attr is multi-valued, so see if it the patch will delete
          // all values of a required multi-valued attribute.
          for (SCIMAttributeValue value : attr.getValues())
          {
            if(value.isComplex())
            {
              String operation = value.getSubAttributeValue(
                  "operation", AttributeValueResolver.STRING_RESOLVER);
              if (!"delete".equalsIgnoreCase(operation))
              {
                // At least one value is being added so this attribute should
                // never be empty
                valuesDeleted = 0;
                break;
              }
              else
              {
                valuesDeleted++;
              }
            }
          }
          if(valuesDeleted > 0)
          {
            // The patch only contained delete operations. Make sure not all
            // of the current values are being deleted.
            List<SCIMAttribute> currentAttribute = mapper.toSCIMAttributes(
                currentEntry, new SCIMQueryAttributes(
                Collections.singletonMap(attr.getAttributeDescriptor(),
                    Collections.<AttributeDescriptor>emptySet())),
                ldapInterface);
            if(!currentAttribute.isEmpty() &&
                currentAttribute.get(0).getValues().length <= valuesDeleted)
            {
              throw new InvalidResourceException("Multi-valued attribute '" +
                  schema + ":" + attr.getAttributeDescriptor().getName() +
                  "' is required and must have at least one value");
            }
          }
        }
      }
    }
  }

}
