/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.antispam.internal.simple;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.antispam.AntiSpamException;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.WikiReference;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

@Component
@Singleton
public class DefaultSpamCheckerModel implements SpamCheckerModel
{
    private static final DocumentReference ADDRESSES_DOCUMENT_REFERENCE =
        new DocumentReference("xwiki", "AntiSpam", "IPAddresses");

    private static final DocumentReference KEYWORDS_DOCUMENT_REFERENCE =
        new DocumentReference("xwiki", "AntiSpam", "Keywords");

    private static final SpaceReference KEYWORDS_SPACE_REFERENCE =
        new SpaceReference("AntiSpam", new WikiReference("xwiki"));

    private static final DocumentReference DISABLED_USERS_DOCUMENT_REFERENCE =
        new DocumentReference("xwiki", "AntiSpam", "DisabledUsers");

    private static final DocumentReference CONFIG_DOCUMENT_REFERENCE =
        new DocumentReference("xwiki", "AntiSpam", "AntiSpamConfig");

    private static final EntityReference USER_XCLASS_REFERENCE = new EntityReference("XWikiUsers",
        EntityType.DOCUMENT, new EntityReference("XWiki", EntityType.SPACE));

    private static final DocumentReference CONFIG_XCLASS_REFERENCE =
        new DocumentReference("xwiki", "AntiSpam", "AntiSpamConfigClass");

    private static final DocumentReference EXCLUDES_DOCUMENT_REFERENCE =
        new DocumentReference("xwiki", "AntiSpam", "Excludes");

    @Inject
    private Logger logger;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private EntityReferenceSerializer<String> entityReferenceSerializer;

    @Override
    public List<String> getSpamAddresses() throws AntiSpamException
    {
        List<String> addresses;
        try {
            XWikiContext xcontext = getXWikiContext();
            XWikiDocument addressDocument = getDocument(ADDRESSES_DOCUMENT_REFERENCE, xcontext);
            // Parse the IPs from the content
            addresses = IOUtils.readLines(new StringReader(addressDocument.getContent()));
        } catch (Exception e) {
            throw new AntiSpamException(String.format("Failed to get document containing spam IP addresses [%s]",
                ADDRESSES_DOCUMENT_REFERENCE.toString()), e);
        }
        return addresses;
    }

    @Override
    public List<String> getSpamKeywords() throws AntiSpamException
    {
        List<String> keywords;
        try {
            XWikiContext xcontext = getXWikiContext();
            // Is there a current-wiki-specific keywords list?
            DocumentReference wikiKeywordsReference = new DocumentReference(
                String.format("Keywords-%s", xcontext.getWikiId()), KEYWORDS_SPACE_REFERENCE);
            XWikiDocument keywordsDocument = getDocument(wikiKeywordsReference, xcontext);
            if (keywordsDocument.isNew()) {
                keywordsDocument = getDocument(KEYWORDS_DOCUMENT_REFERENCE, xcontext);
            }
            // Parse the Keywords from the content, ignoring comments
            keywords = parseContentByLine(keywordsDocument.getContent());
        } catch (Exception e) {
            throw new AntiSpamException(String.format("Failed to get document containing spam keywords [%s]",
                KEYWORDS_DOCUMENT_REFERENCE.toString()), e);
        }
        return keywords;
    }

    @Override
    public void logSpamAddress(String ip) throws AntiSpamException
    {
        try {
            XWikiContext xcontext = getXWikiContext();
            XWikiDocument addressDocument = getDocument(ADDRESSES_DOCUMENT_REFERENCE, xcontext);
            // Only log the IP if it's not already in the list
            List<String> loggedIPs = parseContentByLine(addressDocument.getContent());
            if (!loggedIPs.contains(ip)) {
                addressDocument.setContent(addressDocument.getContent() + "\n" + ip);
                getXWiki(xcontext).saveDocument(addressDocument, "Adding new spammer ip", true, xcontext);
            }
        } catch (Exception e) {
            throw new AntiSpamException(String.format("Failed to add ip [%s]to containing spam IP addresses[%s]",
                ip, KEYWORDS_DOCUMENT_REFERENCE.toString()), e);
        }
    }

    @Override
    public void disableUser(DocumentReference authorReference) throws AntiSpamException
    {
        try {
            XWikiContext xcontext = getXWikiContext();
            XWikiDocument authorDocument = getDocument(authorReference, xcontext);
            if (!authorDocument.isNew()) {
                BaseObject xwikiUserObject = authorDocument.getXObject(USER_XCLASS_REFERENCE);
                xwikiUserObject.set("active", 0, xcontext);
                getXWiki(xcontext).saveDocument(authorDocument, "Disabling user considered as spammer", true, xcontext);
            }
        } catch (Exception e) {
            throw new AntiSpamException(String.format("Failed to disable user [%s]", authorReference), e);
        }
    }

    @Override
    public void logDisabledUser(DocumentReference authorReference) throws AntiSpamException
    {
        try {
            XWikiContext xcontext = getXWikiContext();
            XWikiDocument disabledUsersDocument = getDocument(DISABLED_USERS_DOCUMENT_REFERENCE, xcontext);
            // Only log the user if he's not already in the list
            List<String> disabledUsers = parseContentByLine(disabledUsersDocument.getContent());
            String authorReferenceAsString = this.entityReferenceSerializer.serialize(authorReference);
            if (!disabledUsers.contains(authorReferenceAsString)) {
                disabledUsersDocument.setContent(disabledUsersDocument.getContent() + "\n" + authorReferenceAsString);
                getXWiki(xcontext).saveDocument(disabledUsersDocument, String.format("Adding user [%s]",
                    authorReference), true, xcontext);
            }
        } catch (Exception e) {
            throw new AntiSpamException(String.format("Failed to log disabled spam user [%s] to [%s]",
                authorReference, DISABLED_USERS_DOCUMENT_REFERENCE.toString()), e);
        }
    }

    @Override
    public boolean iSpamCheckingActive()
    {
        boolean isSpamCheckingActive;
        try {
            BaseObject configObject = getConfigOject();
            if (configObject != null) {
                int active = configObject.getIntValue("active");
                // By default spam checking is true, unless set to false
                isSpamCheckingActive = active == 0 ? false : true;
            } else {
                // No xobject, we consider it's active
                isSpamCheckingActive = true;
            }
        } catch (Exception e) {
            this.logger.error("Failed to access AntiSpam configuration", e);
            isSpamCheckingActive = true;
        }
        return isSpamCheckingActive;
    }

    @Override
    public int getXFFHeaderIPPosition()
    {
        int position;
        try {
            BaseObject configObject = getConfigOject();
            if (configObject != null) {
                // 0 means last position in the XFF header list
                // 1 means last but one position in the XFF header list
                position = configObject.getIntValue("xffHeaderIPPosition", -1);
            } else {
                position = -1;
            }
        } catch (Exception e) {
            this.logger.error("Failed to get XFF header IP position", e);
            position = -1;
        }
        return position;
    }

    @Override
    public List<String> getExcludedSpaces()
    {
        List<String> excludes;
        try {
            XWikiContext xcontext = getXWikiContext();
            XWikiDocument excludesDocument = getDocument(EXCLUDES_DOCUMENT_REFERENCE, xcontext);
            excludes = parseContentByLine(excludesDocument.getContent());
        } catch (Exception e) {
            this.logger.error("Failed to get document containing excludes [{}]",
                EXCLUDES_DOCUMENT_REFERENCE.toString(), e);
            excludes = Collections.emptyList();
        }
        return excludes;
    }

    private BaseObject getConfigOject() throws Exception
    {
        XWikiContext xcontext = getXWikiContext();
        XWikiDocument configDocument = getDocument(CONFIG_DOCUMENT_REFERENCE, xcontext);
        return configDocument.getXObject(CONFIG_XCLASS_REFERENCE);
    }

    private XWikiDocument getDocument(EntityReference reference, XWikiContext xcontext) throws Exception
    {
        XWiki xwiki = xcontext.getWiki();
        return xwiki.getDocument(reference, xcontext);
    }

    private XWikiContext getXWikiContext()
    {
        return this.contextProvider.get();
    }

    private XWiki getXWiki(XWikiContext xcontext)
    {
        return xcontext.getWiki();
    }

    private List<String> parseContentByLine(String content) throws IOException
    {
        List<String> result = new ArrayList<String>();
        for (String line : IOUtils.readLines(new StringReader(content))) {
            if (!StringUtils.isBlank(line) && !line.startsWith("#")) {
                result.add(line);
            }
        }
        return result;
    }
}
