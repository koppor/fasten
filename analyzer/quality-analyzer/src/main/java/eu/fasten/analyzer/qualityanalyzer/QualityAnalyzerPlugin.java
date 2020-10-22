/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.fasten.analyzer.qualityanalyzer;

import eu.fasten.analyzer.qualityanalyzer.data.*;

import eu.fasten.core.data.metadatadb.MetadataDao;

import eu.fasten.core.data.metadatadb.codegen.tables.records.FilesRecord;
import eu.fasten.core.data.metadatadb.codegen.tables.records.PackageVersionsRecord;
import eu.fasten.core.data.metadatadb.codegen.tables.records.PackagesRecord;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;

import org.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class MetadataUtils {

    private final Logger logger = LoggerFactory.getLogger(MetadataUtils.class.getName());

    DSLContext selectedContext = null;

    public MetadataUtils(DSLContext context) {
        this.selectedContext = contexts;
    }

    /**
     *
     * @param forge         String which could have value MVN, PyPI or C.
     * @param jsonRecord    Object that contains quality analysis metadata.
     */
    public void insertMetadataIntoDB(String forge, JSONObject jsonRecord) {

        List<CallableHolder> callableHolderList = getCallables(forge, jsonRecord);

        var metadataDao = new MetadataDao(selectedContext);
        for(CallableHolder callable : callableHolderList){
            metadataDao.updateCallableMetadata(callable.getModuleId(), callable.getFastenUri(), callable.isInternal(), callable.getCallableMetadata());
        }
    }


    private List<CallableHolder> getCallables(String forge, JSONObject jsonRecord) {

        //1. get package and version
        //2. get packageversionid
        //3. getfileid
        //4. getmoduleid
        //5. get callables for module id
        //6. filter callables that contain "good" values for start and end line

        String product = null;
        String version = null;

        if (jsonRecord.has("input")) {
            product = jsonRecord.getJSONObject("input").getString("product");
            version = jsonRecord.getJSONObject("input").getString("version");
        }

        //TODO: check for null values here

        Long pckVersionId = getPackageVersionId(product, forge, version);

        String path = jsonRecord.getJSONObject("payload").getString("filepath");
        int lineStart = Integer.parseInt(jsonRecord.getJSONObject("payload").getJSONObject("metrics").getString("start_line"));
        int lineEnd = Integer.parseInt(jsonRecord.getJSONObject("payload").getJSONObject("metrics").getString("end_line"));

        Long fileId = getFileId(pckVersionId, path);
        List<Long> modulesId = getModuleIds(fileId);

        ArrayList<CallableHolder> callables = new ArrayList<CallableHolder>();

        if(!modulesId.isEmpty()) {

            for(Long moduleId : modulesId) {
                callables.addAll(getCallablesInformation(moduleId, lineStart, lineEnd));
            }

            //TODO: we need a unique record here!
            logger.info("Found " + callables.size() + " methods for which startLine= " + lineStart + " and endLine= " + lineEnd);

        }

        return callables;

    }

    /**
     * Retrieves the package_version_id given the purl of the package version.
     * @param purl - follows purl specifications
     * @return negative if it cannot be found
     */
    private Long getPackageVersionId(String coordinate, String forge, String version) {

        logger.info("Looking for package_version_id of " + coordinate);

        Long packageId = getPackageIdFromCoordinate(
                coordinate,
                forge);

        if (packageId != null) {
            return getPackageVersionIdFromVersion(
                    packageId,
                    version);
        }

        return -1L;
    }



    /**
     * Finds the ID of the package given coordinate and forge.
     * This is ecosystem agnostic
     * @param coordinate - includes information about the package
     * @param forge - ['mvn', 'PyPI', 'Debian']
     * @return - Value of the package ID if found, null otherwise.
     */
    private Long getPackageIdFromCoordinate(String coordinate, String forge) {

        PackagesRecord record = (PackagesRecord) selectedContext.select()
                .from(Packages.PACKAGES)
                .where(Packages.PACKAGES.PACKAGE_NAME.equal(coordinate))
                .and(Packages.PACKAGES.FORGE.equal(forge))
                .fetchOne();

        if (record != null) {
            return record.component1();
        }

        return null;
    }

    /**
     * Finds the ID of the package_version in all the version of the package.
     * @param pkgId - ID of the package
     * @param version - String of the version of the package_version
     * @return - Value of the package_version if found, -1 otherwise.
     */
    private Long getPackageVersionIdFromVersion(Long pkgId, String version) {

        // Find the package version record
        PackageVersionsRecord pkgVersionRecord = (PackageVersionsRecord) selectedContext.select()
                .from(PackageVersions.PACKAGE_VERSIONS)
                .where(PackageVersions.PACKAGE_VERSIONS.PACKAGE_ID.equal(pkgId))
                .and(PackageVersions.PACKAGE_VERSIONS.VERSION.equal(version))
                .fetchOne();

        if(pkgVersionRecord != null) {
            return pkgVersionRecord.getId();
        }

        return -1L;

    }
    /**
     * Retrieve the fileId of the file
     * @param packageVersionId - package version ID
     * @param filepath - path to the file
     * @return - Long value of fileId or -1 if the file cannot be found
     */
    private Long getFileId(Long packageVersionId, String filepath) {
        // For the demo, just cut out the filename, without the path
        var splits = filepath.split("/");
        var filename = splits[splits.length - 1];

        FilesRecord fr = (FilesRecord) selectedContext.select()
                .from(Files.FILES)
                .where(Files.FILES.PACKAGE_VERSION_ID.equal(packageVersionId))
                .and(Files.FILES.PATH.equal(filename))
                .fetchOne();

        if (fr != null) {
            return fr.getId();
        }

        return -1L;
    }

    /**
     * Gets the moduleId that corresponds to the file.
     * @param fileId - Long fileId
     * @return list of module Ids
     */
    public List<Long> getModuleIds(Long fileId) {
        List<Long> moduleIds = new ArrayList<>();
        Result<Record> mcr = selectedContext.select()
                .from(ModuleContents.MODULE_CONTENTS)
                .where(ModuleContents.MODULE_CONTENTS.FILE_ID.equal(fileId))
                .fetch();

        if(mcr.isNotEmpty()) {
            for (Record record : mcr) {
                moduleIds.add((Long) record.get(0));
            }
        }

        return moduleIds;//we cannot return -1 here since that implies external callable
    }

    /**
     * Retrieves the callables information from the DB with a given values for the start and end line.
     *
     * @param moduleId - Long ID of the file where the callable was changed.
     * @param startLine - int value that indicates start callable line in source file.
     * @param endLine - int value that indicates the last callable line in source file.
     *
     * @return Long ID of the callable (-1L if it cannot find it)
     */
    private List<CallableHolder> getCallablesInformation(Long moduleId, int lineStart, int lineEnd)  {

        List<CallableHolder> calls = new ArrayList<>();

        // Get all the records with the moduleId given
        //and line start and line end are as given
        //we could use line start *or* line end
        Result<Record> crs = selectedContext.select()
                .from(Callables.CALLABLES)
                .where(Callables.CALLABLES.MODULE_ID.equal(moduleId))
                .and(Callables.CALLABLES.LINE_START.equal(lineStart))
                .and(Callables.CALLABLES.LINE_END.equal(lineEnd))
                .fetch();

        for (Record cr : crs) {

            // Create callable object
            CallableHolder ch = new CallableHolder(cr);
            //filter and store callable only if start and end line overlap with input
            calls.add(ch);
        }
        return calls;
    }
}
