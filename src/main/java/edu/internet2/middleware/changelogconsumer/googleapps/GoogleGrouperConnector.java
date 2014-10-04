/*
 * Licensed to the University Corporation for Advanced Internet Development,
 * Inc. (UCAID) under one or more contributor license agreements.  See the
 * NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The UCAID licenses this file to You under the Apache
 * License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.internet2.middleware.changelogconsumer.googleapps;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.admin.directory.Directory;
import com.google.api.services.admin.directory.model.Group;
import com.google.api.services.admin.directory.model.Member;
import com.google.api.services.admin.directory.model.User;
import com.google.api.services.admin.directory.model.UserName;
import com.google.api.services.groupssettings.Groupssettings;
import com.google.api.services.groupssettings.model.Groups;
import edu.internet2.middleware.changelogconsumer.googleapps.cache.Cache;
import edu.internet2.middleware.changelogconsumer.googleapps.cache.GoogleCacheManager;
import edu.internet2.middleware.changelogconsumer.googleapps.utils.AddressFormatter;
import edu.internet2.middleware.changelogconsumer.googleapps.utils.GoogleAppsSyncProperties;
import edu.internet2.middleware.grouper.*;
import edu.internet2.middleware.grouper.attr.AttributeDef;
import edu.internet2.middleware.grouper.attr.AttributeDefName;
import edu.internet2.middleware.grouper.attr.AttributeDefType;
import edu.internet2.middleware.grouper.attr.assign.AttributeAssign;
import edu.internet2.middleware.grouper.attr.finder.AttributeDefFinder;
import edu.internet2.middleware.grouper.attr.finder.AttributeDefNameFinder;
import edu.internet2.middleware.grouper.internal.dao.QueryOptions;
import edu.internet2.middleware.grouper.misc.GrouperDAOFactory;
import edu.internet2.middleware.grouper.util.GrouperUtil;
import edu.internet2.middleware.subject.Subject;
import edu.internet2.middleware.subject.provider.SubjectTypeEnum;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by jgasper on 10/3/14.
 */
public class GoogleGrouperConnector {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleGrouperConnector.class);

    public static final String SYNC_TO_GOOGLE = "syncToGoogle";
    public static final String GOOGLE_PROVISIONER = "googleProvisioner";
    public static final String ATTRIBUTE_CONFIG_STEM = "etc:attribute";
    public static final String GOOGLE_CONFIG_STEM = ATTRIBUTE_CONFIG_STEM + ":" + GOOGLE_PROVISIONER;
    public static final String SYNC_TO_GOOGLE_NAME = GOOGLE_CONFIG_STEM + ":" + SYNC_TO_GOOGLE;

    /** Google Directory services client*/
    private Directory directoryClient;

    /** Google Groupssettings services client*/
    private Groupssettings groupssettingsClient;

    private AttributeDefName syncAttribute;

    /** Global instance of the JSON factory. */
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    private AddressFormatter addressFormatter = new AddressFormatter();

    //The Google Objects hang around a lot longer due to Google API constraints, so they are stored in a static GoogleCacheManager class.
    //Grouper ones are easier to refresh.
    private Cache<Subject> grouperSubjects = new Cache<Subject>();
    private Cache<edu.internet2.middleware.grouper.Group> grouperGroups = new Cache<edu.internet2.middleware.grouper.Group>();
    private HashMap<String, String> syncedObjects = new HashMap<String, String>();

    private String consumerName;

    private GoogleAppsSyncProperties properties;

    public void initialize(String consumerName, GoogleAppsSyncProperties properties) throws GeneralSecurityException, IOException {
        this.consumerName = consumerName;
        this.properties = properties;

        final HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        final GoogleCredential googleDirectoryCredential = GoogleAppsSdkUtils.getGoogleDirectoryCredential(
                properties.getServiceAccountEmail(), properties.getServiceAccountPKCS12FilePath(), properties.getServiceImpersonationUser(),
                httpTransport, JSON_FACTORY);

        final GoogleCredential googleGroupssettingsCredential = GoogleAppsSdkUtils.getGoogleGroupssettingsCredential(
                properties.getServiceAccountEmail(), properties.getServiceAccountPKCS12FilePath(), properties.getServiceImpersonationUser(),
                httpTransport, JSON_FACTORY);

        directoryClient = new Directory.Builder(httpTransport, JSON_FACTORY, googleDirectoryCredential)
                .setApplicationName("Google Apps Grouper Provisioner")
                .build();

        groupssettingsClient = new Groupssettings.Builder(httpTransport, JSON_FACTORY, googleGroupssettingsCredential)
                .setApplicationName("Google Apps Grouper Provisioner")
                .build();

        addressFormatter.setGroupIdentifierExpression(properties.getGroupIdentifierExpression())
                .setSubjectIdentifierExpression(properties.getSubjectIdentifierExpression())
                .setDomain(properties.getGoogleDomain());

        GoogleCacheManager.googleUsers().setCacheValidity(properties.getGoogleUserCacheValidity());
        populateGooUsersCache(directoryClient);

        GoogleCacheManager.googleGroups().setCacheValidity(properties.getGoogleGroupCacheValidity());
        populateGooGroupsCache(directoryClient);

        grouperSubjects.setCacheValidity(5);
        grouperSubjects.seed(1000);

        grouperGroups.setCacheValidity(5);
        grouperGroups.seed(100);
    }


    public void populateGooUsersCache(Directory directory) {
        LOG.debug("Google Apps Consumer '{}' - Populating the userCache.", consumerName);

        if (GoogleCacheManager.googleUsers() == null || GoogleCacheManager.googleUsers().isExpired()) {
            try {
                final List<User> list = GoogleAppsSdkUtils.retrieveAllUsers(directoryClient);
                GoogleCacheManager.googleUsers().seed(list);

            } catch (GoogleJsonResponseException e) {
                LOG.error("Google Apps Consumer '{}' - Something bad happened when populating the userCache: {}", consumerName, e);
            } catch (IOException e) {
                LOG.error("Google Apps Consumer '{}' - Something bad happened when populating the userCache: {}", consumerName, e);
            }
        }
    }

    public void populateGooGroupsCache(Directory directory) {
        LOG.debug("Google Apps Consumer '{}' - Populating the groupCache.", consumerName);

        if (GoogleCacheManager.googleGroups() == null || GoogleCacheManager.googleGroups().isExpired()) {
            try {
                final List<Group> list = GoogleAppsSdkUtils.retrieveAllGroups(directoryClient);
                GoogleCacheManager.googleGroups().seed(list);

            } catch (GoogleJsonResponseException e) {
                LOG.error("Google Apps Consumer '{}' - Something bad happened when populating the groupCache: {}", consumerName, e);
            } catch (IOException e) {
                LOG.error("Google Apps Consumer '{}' - Something bad happened when populating the groupCache: {}", consumerName, e);
            }
        }
    }

    public Group fetchGooGroup(String groupKey) throws IOException {
        Group group = GoogleCacheManager.googleGroups().get(groupKey);
        if (group == null) {
            group = GoogleAppsSdkUtils.retrieveGroup(directoryClient, groupKey);

            if (group != null) {
                GoogleCacheManager.googleGroups().put(group);
            }
        }

        return group;
    }

    public User fetchGooUser(String userKey) {
        User user = GoogleCacheManager.googleUsers().get(userKey);
        if (user == null) {
            try {
                user = GoogleAppsSdkUtils.retrieveUser(directoryClient, userKey);
            } catch (IOException e) {
                LOG.warn("Google Apps Consume '{}' - Error fetching user ({}) from Google: {}", new Object[]{consumerName, userKey, e.getMessage()});
            }

            if (user != null) {
                GoogleCacheManager.googleUsers().put(user);
            }
        }

        return user;
    }

    public edu.internet2.middleware.grouper.Group fetchGrouperGroup(String groupName) {
        edu.internet2.middleware.grouper.Group group = grouperGroups.get(groupName);
        if (group == null) {
            group = GroupFinder.findByName(GrouperSession.staticGrouperSession(false), groupName, false);

            if (group != null) {
                grouperGroups.put(group);
            }
        }

        return group;
    }

    public Subject fetchGrouperSubject(String sourceId, String subjectId) {
        Subject subject = grouperSubjects.get(sourceId + "__" + subjectId);
        if (subject == null) {
            subject = SubjectFinder.findByIdAndSource(sourceId, subjectId, false);

            if (subject != null) {
                grouperSubjects.put(subject);
            }
        }

        return subject;
    }

    public User createGooUser(Subject subject) throws IOException {
        final String email = subject.getAttributeValue("email");
        final String subjectName = subject.getName();

        User newUser = null;
        if (properties.shouldProvisionUsers()) {
            newUser = new User();
            newUser.setPassword(new BigInteger(130, new SecureRandom()).toString(32))
                    .setPrimaryEmail(email != null ? email : addressFormatter.qualifySubjectAddress(subject.getId()))
                    .setIncludeInGlobalAddressList(properties.shouldIncludeUserInGlobalAddressList())
                    .setName(new UserName())
                    .getName().setFullName(subjectName);

            if (properties.useSimpleSubjectNaming()) {
                final String[] subjectNameSplit = subjectName.split(" ");
                newUser.getName().setFamilyName(subjectNameSplit[subjectNameSplit.length - 1])
                        .setGivenName(subjectNameSplit[0]);

            } else {
                newUser.getName().setFamilyName(subject.getAttributeValue(properties.getSubjectSurnameField()))
                        .setGivenName(subject.getAttributeValue(properties.getSubjectGivenNameField()));
            }

            newUser = GoogleAppsSdkUtils.addUser(directoryClient, newUser);
            GoogleCacheManager.googleUsers().put(newUser);
        }

        return newUser;
    }

    public void createGooMember(Group group, User user, String role) throws IOException {
        final Member gMember = new Member();
        gMember.setEmail(user.getPrimaryEmail())
                .setRole(role);

        GoogleAppsSdkUtils.addGroupMember(directoryClient, group, gMember);
    }

    public void createGooGroupIfNecessary(edu.internet2.middleware.grouper.Group grouperGroup) throws IOException {
        final String groupKey = addressFormatter.qualifyGroupAddress(grouperGroup.getName());

        Group googleGroup = fetchGooGroup(groupKey);
        if (googleGroup == null) {
            googleGroup = new Group();
            googleGroup.setName(grouperGroup.getDisplayExtension())
                    .setEmail(groupKey)
                    .setDescription(grouperGroup.getDescription());
            GoogleCacheManager.googleGroups().put(GoogleAppsSdkUtils.addGroup(directoryClient, googleGroup));

            final Groups groupSettings = GoogleAppsSdkUtils.retrieveGroupSettings(groupssettingsClient, groupKey);
            final Groups defaultGroupSettings = properties.getDefaultGroupSettings();
            groupSettings.setWhoCanViewMembership(defaultGroupSettings.getWhoCanViewMembership())
                    .setWhoCanInvite(defaultGroupSettings.getWhoCanInvite())
                    .setAllowExternalMembers(defaultGroupSettings.getAllowExternalMembers())
                    .setWhoCanPostMessage(defaultGroupSettings.getWhoCanPostMessage())
                    .setAllowWebPosting(defaultGroupSettings.getAllowWebPosting())
                    .setPrimaryLanguage(defaultGroupSettings.getPrimaryLanguage())
                    .setMaxMessageBytes(defaultGroupSettings.getMaxMessageBytes())
                    .setIsArchived(defaultGroupSettings.getIsArchived())
                    .setMessageModerationLevel(defaultGroupSettings.getMessageModerationLevel())
                    .setSpamModerationLevel(defaultGroupSettings.getSpamModerationLevel())
                    .setReplyTo(defaultGroupSettings.getReplyTo())
                    .setCustomReplyTo(defaultGroupSettings.getCustomReplyTo())
                    .setSendMessageDenyNotification(defaultGroupSettings.getSendMessageDenyNotification())
                    .setDefaultMessageDenyNotificationText(defaultGroupSettings.getDefaultMessageDenyNotificationText())
                    .setShowInGroupDirectory(defaultGroupSettings.getShowInGroupDirectory())
                    .setAllowGoogleCommunication(defaultGroupSettings.getAllowGoogleCommunication())
                    .setMembersCanPostAsTheGroup(defaultGroupSettings.getMembersCanPostAsTheGroup())
                    .setMessageDisplayFont(defaultGroupSettings.getMessageDisplayFont())
                    .setIncludeInGlobalAddressList(defaultGroupSettings.getIncludeInGlobalAddressList());
            GoogleAppsSdkUtils.updateGroupSettings(groupssettingsClient, groupKey, groupSettings);

            Set<edu.internet2.middleware.grouper.Member> members = grouperGroup.getMembers();
            for (edu.internet2.middleware.grouper.Member member : members) {
                if (member.getSubjectType() == SubjectTypeEnum.PERSON) {
                    Subject subject = fetchGrouperSubject(member.getSubjectId(), member.getSubjectSourceId());
                    String userKey = addressFormatter.qualifySubjectAddress(subject.getId());
                    User user = fetchGooUser(userKey);

                    if (user == null) {
                        user = createGooUser(subject);
                    }

                    if (user != null) {
                        createGooMember(googleGroup, user, "MEMBER");
                    }
                }
            }
        } else {
            Groups groupssettings = GoogleAppsSdkUtils.retrieveGroupSettings(groupssettingsClient, groupKey);

            if (groupssettings.getArchiveOnly().equalsIgnoreCase("true")) {
                groupssettings.setArchiveOnly("false");
                GoogleAppsSdkUtils.updateGroupSettings(groupssettingsClient, groupKey, groupssettings);
            }
        }
    }

    public void deleteGooGroup(edu.internet2.middleware.grouper.Group group) throws IOException {
        deleteGooGroupByName(group.getName());
    }

    public void deleteGooGroupByName(String groupName) throws IOException {
        final String groupKey = addressFormatter.qualifyGroupAddress(groupName);
        deleteGooGroupByEmail(groupKey);

        grouperGroups.remove(groupName);
        syncedObjects.remove(groupName);
    }

    public void deleteGooGroupByEmail(String groupKey) throws IOException {
        if (properties.getHandleDeletedGroup().equalsIgnoreCase("archive")) {
            Groups gs = GoogleAppsSdkUtils.retrieveGroupSettings(groupssettingsClient, groupKey);
            gs.setArchiveOnly("true");
            GoogleAppsSdkUtils.updateGroupSettings(groupssettingsClient, groupKey, gs);

        } else if (properties.getHandleDeletedGroup().equalsIgnoreCase("delete")) {
            GoogleAppsSdkUtils.removeGroup(directoryClient, groupKey);
            GoogleCacheManager.googleGroups().remove(groupKey);
        }
        //else "ignore" (we do nothing)

    }


    /**
     * Finds the AttributeDefName specific to this GoogleApps ChangeLog Consumer instance.
     * @return The AttributeDefName for this GoogleApps ChangeLog Consumer
     */
    public AttributeDefName getGoogleSyncAttribute() {
        LOG.debug("Google Apps Consumer '{}' - looking for attribute: {}", consumerName, SYNC_TO_GOOGLE_NAME + consumerName);

        if (syncAttribute != null) {
            return syncAttribute;
        }

        AttributeDefName attrDefName = AttributeDefNameFinder.findByName(SYNC_TO_GOOGLE_NAME + consumerName, false);

        if (attrDefName == null) {
            Stem googleStem = StemFinder.findByName(GrouperSession.staticGrouperSession(), GOOGLE_CONFIG_STEM, false);

            if (googleStem == null) {
                LOG.info("Google Apps Consumer '{}' - {} stem not found, creating it now", consumerName, GOOGLE_CONFIG_STEM);
                final Stem etcAttributeStem = StemFinder.findByName(GrouperSession.staticGrouperSession(), ATTRIBUTE_CONFIG_STEM, false);
                googleStem = etcAttributeStem.addChildStem(GOOGLE_PROVISIONER, GOOGLE_PROVISIONER);
            }

            AttributeDef syncAttrDef = AttributeDefFinder.findByName(SYNC_TO_GOOGLE_NAME + "Def", false);
            if (syncAttrDef == null) {
                LOG.info("Google Apps Consumer '{}' - {} AttributeDef not found, creating it now", consumerName, SYNC_TO_GOOGLE + "Def");
                syncAttrDef = googleStem.addChildAttributeDef(SYNC_TO_GOOGLE + "Def", AttributeDefType.attr);
                syncAttrDef.setAssignToGroup(true);
                syncAttrDef.setAssignToStem(true);
                syncAttrDef.setMultiAssignable(true);
                syncAttrDef.store();
            }

            LOG.info("Google Apps Consumer '{}' - {} attribute not found, creating it now", consumerName, SYNC_TO_GOOGLE_NAME + consumerName);
            attrDefName = googleStem.addChildAttributeDefName(syncAttrDef, SYNC_TO_GOOGLE + consumerName, SYNC_TO_GOOGLE + consumerName);
        }

        syncAttribute = attrDefName;

        return attrDefName;
    }

    public boolean shouldSyncGroup(edu.internet2.middleware.grouper.Group group) {
        boolean result;

        final String groupName = group.getName();

        if (syncedObjects.containsKey(groupName)) {
            result = syncedObjects.get(groupName).equalsIgnoreCase("yes");

        } else {
            result = group.getAttributeDelegate().retrieveAssignments(syncAttribute).size() > 0 || shouldSyncStem(group.getParentStem());
            syncedObjects.put(groupName, result ? "yes" : "no");
        }

        return result;
    }

    public boolean shouldSyncStem(Stem stem) {
        boolean result;

        final String stemName = stem.getName();

        if (syncedObjects.containsKey(stemName)) {
            result = syncedObjects.get(stemName).equalsIgnoreCase("yes");

        } else {
            result = stem.getAttributeDelegate().retrieveAssignments(syncAttribute).size() > 0 || !stem.isRootStem() && shouldSyncStem(stem.getParentStem());

            syncedObjects.put(stemName, result ? "yes" : "no");
        }

        return result;
    }

    public void cacheSynedObjects() {
        cacheSynedObjects(false);
    }
    public void cacheSynedObjects(boolean fullyPopulate) {
        /* Future: API 2.3.0 has support for getting a list of stems and groups using the Finder objects. */

        final ArrayList<String> ids = new ArrayList<String>();

        //First the users
        Set<AttributeAssign> attributeAssigns = GrouperDAOFactory.getFactory()
                .getAttributeAssign().findStemAttributeAssignments(null, null, GrouperUtil.toSet(syncAttribute.getId()), null, null, true, false);

        for (AttributeAssign attributeAssign : attributeAssigns) {
            ids.add(attributeAssign.getOwnerStemId());
        }
        final Set<Stem> stems = StemFinder.findByUuids(GrouperSession.staticGrouperSession(), ids, new QueryOptions());
        for (Stem stem : stems) {
            syncedObjects.put(stem.getName(), "yes");

            if (fullyPopulate) {
                for (edu.internet2.middleware.grouper.Group group : stem.getChildGroups(Stem.Scope.SUB)) {
                    syncedObjects.put(group.getName(), "yes");
                }
            }
        }

        //Now for the Groups
        attributeAssigns = GrouperDAOFactory.getFactory()
                .getAttributeAssign().findGroupAttributeAssignments(null, null, GrouperUtil.toSet(syncAttribute.getId()), null, null, true, false);

        for (AttributeAssign attributeAssign : attributeAssigns) {
            final edu.internet2.middleware.grouper.Group group = GroupFinder.findByUuid(GrouperSession.staticGrouperSession(), attributeAssign.getOwnerGroupId(), false);
            syncedObjects.put(group.getName(), "yes");
        }
    }


    public void removeGooMembership(String groupName, Subject subject) throws IOException {
        final String groupKey = addressFormatter.qualifyGroupAddress(groupName);
        final String userKey = addressFormatter.qualifySubjectAddress(subject.getId());

        GoogleAppsSdkUtils.removeGroupMember(directoryClient, groupKey, userKey);

        if (properties.shouldDeprovisionUsers()) {
            //FUTURE: check if the user has other memberships and if not, initiate the removal here.
        }

        }

    public Group updateGooGroup(String groupKey, Group group) throws IOException {
        return GoogleAppsSdkUtils.updateGroup(directoryClient, groupKey, group);
    }

    public List<Member> getGooMembership(String groupKey) throws IOException {
        return GoogleAppsSdkUtils.retrieveGroupMembers(directoryClient, groupKey);
    }

    public AddressFormatter getAddressFormatter() {
        return addressFormatter;
    }

    public HashMap<String, String> getSyncedGroupsAndStems() {
        return syncedObjects;
    }
}

