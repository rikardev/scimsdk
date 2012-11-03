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

import com.unboundid.scim.schema.AttributeDescriptor;
import com.unboundid.scim.schema.CoreSchema;
import com.unboundid.scim.schema.ResourceDescriptor;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.unboundid.scim.sdk.StaticUtils.toLowerCase;



/**
 * This class represents a System for Cross-Domain Identity Management (SCIM)
 * object. A SCIM object may be composed of common schema attributes and a
 * collection of attributes from one or more additional schema definitions.
 * This class is not designed to be thread-safe.
 */
public class SCIMObject
{

  /**
   * The set of attributes in this object grouped by the URI of the schema to
   * which they belong.
   */
  private final HashMap<String,LinkedHashMap<String,SCIMAttribute>> attributes;



  /**
   * Create an empty SCIM object that initially has no attributes. The type of
   * resource is not specified.
   */
  public SCIMObject()
  {
    this.attributes =
        new HashMap<String, LinkedHashMap<String, SCIMAttribute>>();
  }


  /**
   * Create a new copy of the provided SCIM object.
   *
   * @param scimObject The SCIMObject to copy.
   */
  public SCIMObject(final SCIMObject scimObject)
  {
    // Since SCIMAttribute is immutable, just copy the maps.
    this.attributes =
        new HashMap<String, LinkedHashMap<String, SCIMAttribute>>();
    for(Map.Entry<String, LinkedHashMap<String, SCIMAttribute>> entry :
        scimObject.attributes.entrySet())
    {
      this.attributes.put(entry.getKey(),
          new LinkedHashMap<String, SCIMAttribute>(entry.getValue()));
    }
  }



  /**
   * Retrieves the set of schemas currently contributing attributes to this
   * object.
   *
   * @return  An immutable collection of the URIs of schemas currently
   *          contributing attributes to this object.
   */
  public Set<String> getSchemas()
  {
    return Collections.unmodifiableSet(attributes.keySet());
  }



  /**
   * Determines whether this object contains any attributes in the specified
   * schema.
   *
   * @param schema  The URI of the schema for which to make the determination.
   *                It must not be {@code null}.
   *
   * @return  {@code true} if this object contains any attributes in the
   *          specified schema, or {@code false} if not.
   */
  public boolean hasSchema(final String schema)
  {
    return attributes.containsKey(toLowerCase(schema));
  }



  /**
   * Retrieves the attribute with the specified name.
   *
   * @param schema  The URI of the schema containing the attribute to retrieve.
   *
   * @param name    The name of the attribute to retrieve. It must not be
   *                {@code null}.
   *
   * @return  The requested attribute from this object, or {@code null} if the
   *          specified attribute is not present in this object.
   */
  public SCIMAttribute getAttribute(final String schema, final String name)
  {
    final LinkedHashMap<String,SCIMAttribute> attrs =
        attributes.get(toLowerCase(schema));

    if (attrs == null)
    {
      return null;
    }
    else
    {
      return attrs.get(toLowerCase(name));
    }
  }



  /**
   * Retrieves the set of attributes in this object from the specified schema.
   *
   * @param schema  The URI of the schema whose attributes are to be retrieved.
   *
   * @return  An immutable collection of the attributes in this object from the
   *          specified schema, or the empty collection if there are no such
   *          attributes.
   */
  public Collection<SCIMAttribute> getAttributes(final String schema)
  {
    final LinkedHashMap<String, SCIMAttribute> attrs =
        attributes.get(toLowerCase(schema));

    if (attrs == null)
    {
      return Collections.emptyList();
    }
    else
    {
      return Collections.unmodifiableCollection(attrs.values());
    }
  }



  /**
   * Determines whether this object contains the specified attribute.
   *
   * @param schema  The URI of the schema containing the attribute.
   * @param name    The name of the attribute for which to make the
   *                determination. It must not be {@code null}.
   *
   * @return  {@code true} if this object contains the specified attribute, or
   *          {@code false} if not.
   */
  public boolean hasAttribute(final String schema, final String name)
  {
    final LinkedHashMap<String, SCIMAttribute> attrs =
        attributes.get(toLowerCase(schema));

    return attrs != null && attrs.containsKey(toLowerCase(name));
  }



  /**
   * Adds the provided attribute to this object. If this object already contains
   * an attribute with the same name from the same schema, then the provided
   * attribute will not be added.
   *
   * @param attribute  The attribute to be added. It must not be {@code null}.
   *
   * @return  {@code true} if the object was updated, or {@code false} if the
   *          object already contained an attribute with the same name.
   */
  public boolean addAttribute(final SCIMAttribute attribute)
  {
    final String lowerCaseSchema = toLowerCase(attribute.getSchema());
    final String lowerCaseName = toLowerCase(attribute.getName());

    LinkedHashMap<String,SCIMAttribute> attrs = attributes.get(lowerCaseSchema);
    if (attrs == null)
    {
      attrs = new LinkedHashMap<String, SCIMAttribute>();
      attrs.put(lowerCaseName, attribute);
      attributes.put(lowerCaseSchema, attrs);
      return true;
    }
    else
    {
      if (attrs.containsKey(lowerCaseName))
      {
        return false;
      }
      else
      {
        attrs.put(lowerCaseName, attribute);
        return true;
      }
    }
  }



  /**
   * Adds the provided attribute to this object, replacing any existing
   * attribute with the same name.
   *
   * @param attribute  The attribute to be added. It must not be {@code null}.
   */
  public void setAttribute(final SCIMAttribute attribute)
  {
    final String lowerCaseSchema = toLowerCase(attribute.getSchema());
    final String lowerCaseName = toLowerCase(attribute.getName());

    LinkedHashMap<String,SCIMAttribute> attrs = attributes.get(lowerCaseSchema);
    if (attrs == null)
    {
      attrs = new LinkedHashMap<String, SCIMAttribute>();
      attrs.put(lowerCaseName, attribute);
      attributes.put(lowerCaseSchema, attrs);
    }
    else
    {
      attrs.put(lowerCaseName, attribute);
    }
  }



  /**
   * Removes the specified attribute from this object.
   *
   * @param schema  The URI of the schema to which the attribute belongs.
   * @param name    The name of the attribute to remove. It must not be
   *                {@code null}.
   *
   * @return  {@code true} if the attribute was removed from the object, or
   *          {@code false} if it was not present.
   */
  public boolean removeAttribute(final String schema, final String name)
  {
    final String lowerCaseSchema = toLowerCase(schema);
    LinkedHashMap<String,SCIMAttribute> attrs = attributes.get(lowerCaseSchema);
    if (attrs == null)
    {
      return false;
    }
    else
    {
      final boolean removed = attrs.remove(toLowerCase(name)) != null;
      if (removed && attrs.isEmpty())
      {
        attributes.remove(lowerCaseSchema);
      }
      return removed;
    }
  }



  /**
   * Determine whether this object matches the provided filter parameters.
   *
   * @param filter  The filter parameters to compare against the object.
   *
   * @return  {@code true} if this object matches the provided filter, and
   *          {@code false} otherwise.
   */
  public boolean matchesFilter(final SCIMFilter filter)
  {
    final SCIMFilterType type = filter.getFilterType();
    final List<SCIMFilter> components = filter.getFilterComponents();

    switch(type)
    {
      case AND:
        for(SCIMFilter component : components)
        {
          if(!matchesFilter(component))
          {
            return false;
          }
        }
        return true;
      case OR:
        for(SCIMFilter component : components)
        {
          if(matchesFilter(component))
          {
            return true;
          }
        }
        return false;
    }

    final String schema = filter.getFilterAttribute().getAttributeSchema();
    final String attributeName = filter.getFilterAttribute().getAttributeName();

    final SCIMAttribute attribute = getAttribute(schema, attributeName);
    return attribute != null && attribute.matchesFilter(filter);

  }



  /**
   * Check this object for potential schema violations based on the provided
   * resource descriptor.
   *
   *
   * @param resourceDescriptor The ResourceDescriptor to check against.
   * @param includeCommonAttributes Whether to enforce the schema for common
   *                                attributes like id and meta.
   * @throws InvalidResourceException If a schema violation is found.
   */
  public void checkSchema(final ResourceDescriptor resourceDescriptor,
                          final boolean includeCommonAttributes)
      throws InvalidResourceException
  {
    // Make sure all required attributes are present
    for(String schema : resourceDescriptor.getAttributeSchemas())
    {
      for(AttributeDescriptor attributeDescriptor :
          resourceDescriptor.getAttributes(schema))
      {
        if(!includeCommonAttributes &&
            (attributeDescriptor.equals(CoreSchema.ID_DESCRIPTOR) ||
                attributeDescriptor.equals(CoreSchema.META_DESCRIPTOR) ||
                attributeDescriptor.equals(CoreSchema.EXTERNAL_ID_DESCRIPTOR)))
        {
          continue;
        }

        if(attributeDescriptor.isRequired())
        {
          if(!hasAttribute(schema, attributeDescriptor.getName()))
          {
            throw new InvalidResourceException("Attribute '" +
                schema + ":" + attributeDescriptor.getName() +
                "' is required");
          }
        }
        else
        {
          Collection<AttributeDescriptor> subAttributes =
              attributeDescriptor.getSubAttributes();
          SCIMAttribute attribute =
              getAttribute(schema, attributeDescriptor.getName());
          if(subAttributes != null && attribute != null)
          {
            // Make sure all required sub-attributes are present as well
            for(AttributeDescriptor subAttribute : subAttributes)
            {
              if(subAttribute.isRequired())
              {
                if(attributeDescriptor.isMultiValued())
                {
                  for(SCIMAttributeValue value : attribute.getValues())
                  {
                    if(!value.hasAttribute(subAttribute.getName()))
                    {
                      throw new InvalidResourceException("Sub-Attribute '" +
                          schema + ":" + attributeDescriptor.getName() + "." +
                          subAttribute.getName() + "' is required for all " +
                          "values of the multi-valued attribute");
                    }
                  }
                }
                else
                {
                  if(!attribute.getValue().hasAttribute(subAttribute.getName()))
                  {
                    throw new InvalidResourceException("Sub-Attribute '" +
                        schema + ":" + attributeDescriptor.getName() + "." +
                        subAttribute.getName() + "' is required");
                  }
                }
              }
            }
          }
        }
      }
    }
  }



  /**
   * {@inheritDoc}
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    SCIMObject that = (SCIMObject) o;

    return attributes.equals(that.attributes);

  }


  /**
   * {@inheritDoc}
   */
  @Override
  public int hashCode() {
    return attributes.hashCode();
  }


  /**
   * {@inheritDoc}
   */
  @Override
  public String toString() {
    return "SCIMObject{" +
      "attributes=" + attributes +
      '}';
  }
}
