/*
 * Copyright (c) 2008-2009 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.labkey.bootstrap;

import java.io.*;
import java.util.*;

/**
 * Extracts modules into exploded module directories
 */
public class ModuleExtractor
{
    public final FilenameFilter moduleArchiveFilter = new FilenameFilter(){
        public boolean accept(File dir, String name)
        {
            return name.toLowerCase().endsWith(ModuleArchive.FILE_EXTENSION);
        }
    };

    protected final File _webAppDirectory;
    protected final ModuleDirectories _moduleDirectories;

    private Set<File> _moduleArchiveFiles;
    private Map<File, Long> _errorArchives;
    private Set<File> _ignoredExplodedDirs;
    private Set<ExplodedModule> _explodedModules;

    private final SimpleLogger _log;

    public ModuleExtractor(File webAppDirectory, SimpleLogger log)
    {
        _webAppDirectory = webAppDirectory;
        _moduleDirectories = new ModuleDirectories(_webAppDirectory);
        _log = log;
    }

    public Collection<ExplodedModule> extractModules()
    {
        Set<File> webAppFiles = getWebAppFiles();
        _moduleArchiveFiles = new HashSet<File>();
        _errorArchives = new HashMap<File,Long>();
        _ignoredExplodedDirs = new HashSet<File>();

        //explode all module archives
        for(File moduleDir : _moduleDirectories.getAllModuleDirectories())
        {
            for(File moduleArchiveFile : moduleDir.listFiles(moduleArchiveFilter))
            {
                try
                {
                    ModuleArchive moduleArchive = new ModuleArchive(moduleArchiveFile, _log);
                    moduleArchive.extractAll();
                    _moduleArchiveFiles.add(moduleArchiveFile);
                }
                catch(IOException e)
                {
                    _log.error("Unable to extract the module archive " + moduleArchiveFile.getPath() + "!", e);
                    _errorArchives.put(moduleArchiveFile, moduleArchiveFile.lastModified());
                }
            }
        }

        //gather all the exploded module directories
        _log.info("Deploying resources from exploded modules to web app directory...");
        _explodedModules = new HashSet<ExplodedModule>();
        for(File moduleDir : _moduleDirectories.getAllModuleDirectories())
        {
            for(File dir : moduleDir.listFiles())
            {
                if(dir.isDirectory())
                {
                    if (dir.isHidden())
                    {
                        _ignoredExplodedDirs.add(dir);
                        continue;
                    }

                    try
                    {
                        ExplodedModule explodedModule = new ExplodedModule(dir);
                        Set<File> moduleWebAppFiles = explodedModule.deployToWebApp(_webAppDirectory);
                        if (null != webAppFiles)
                            webAppFiles.addAll(moduleWebAppFiles);

                        _explodedModules.add(explodedModule);
                    }
                    catch(IOException e)
                    {
                        _log.error("Unable to deploy the resources from the exploded module " + dir.getPath() + " to the web app directory!", e);
                    }
                }
            }
        }

        _log.info("Module extraction and deployment complete.");
        if (null != webAppFiles)
            cleanupWebAppDir(webAppFiles);

        return _explodedModules;
    }

    private void cleanupWebAppDir(Set<File> allowedFiles)
    {
        //delete any file we find in the web app directory that is not in the webAppFiles set
        _log.info("Cleaning up web app directory...");
        cleanupDir(_webAppDirectory, allowedFiles);
        _log.info("Web app directory cleanup complete.");
    }

    private void cleanupDir(File dir, Set<File> allowedFiles)
    {
        for (File file : dir.listFiles())
        {
            if (file.isDirectory())
            {
                cleanupDir(file, allowedFiles);
            }

            if (!allowedFiles.contains(file))
            {
                _log.info("Deleting unused file in web app directory: " + file.getAbsolutePath());
                if (!file.delete())
                    _log.info("WARNING: unable to delete unused web app file " + file.getAbsolutePath());
            }
        }
    }

    protected Set<File> getWebAppFiles()
    {
        //load the apiFiles.list to get a list of all files that are part of the core web app
        File apiFiles = new File(_webAppDirectory, "WEB-INF/apiFiles.list");
        if (!apiFiles.exists())
        {
            _log.info("WARNING: could not find the list of web app files at " + apiFiles.getPath() + ". Automatic cleanup of the web app directory will not occur.");
            return null;
        }

        //file contains one path per line
        Set<File> files = new HashSet<File>();
        BufferedReader reader = null;
        try
        {
            reader = new BufferedReader(new FileReader(apiFiles));
            String line;
            while (null != (line = reader.readLine()))
            {
                files.add(new File(_webAppDirectory, line));
            }
        }
        catch (Exception e)
        {
            _log.info("WARNING: exception while reading " + apiFiles.getPath() + ". "  + e.toString());
            return null;
        }
        finally
        {
            if(null != reader)
            {
                try {reader.close();} catch (Exception ignore){}
            }
        }
        
        return files;
    }


    public List<File> getExplodedModuleDirectories()
    {
        List<File> dirs = new ArrayList<File>();
        for(ExplodedModule expMod : _explodedModules)
        {
            dirs.add(expMod.getRootDirectory());
        }
        return dirs;
    }

    public boolean areModulesModified()
    {
        if(null == _explodedModules)
            return true;

        //check module archives against exploded modules and check for new modules
        for(File moduleDir : _moduleDirectories.getAllModuleDirectories())
        {
            for(File moduleArchiveFile : moduleDir.listFiles(moduleArchiveFilter))
            {
                //if this errored last time and it hasn't changed, just skip it
                if (_errorArchives.containsKey(moduleArchiveFile)
                        && _errorArchives.get(moduleArchiveFile).longValue() == moduleArchiveFile.lastModified())
                    continue;

                //if it's a new module, return true
                if(!_moduleArchiveFiles.contains(moduleArchiveFile))
                {
                    _log.info("New module archive '" + moduleArchiveFile.getPath() + "' found... reloading web application...");
                    return true;
                }

                //if it's been modified since extraction, re-extract it
                ModuleArchive moduleArchive = new ModuleArchive(moduleArchiveFile, _log);
                if(moduleArchive.isModified())
                {
                    try
                    {
                        File explodedDir = moduleArchive.extractAll();
                        new ExplodedModule(explodedDir).deployToWebApp(_webAppDirectory);
                    }
                    catch(IOException e)
                    {
                        _log.error("Could not re-extract module " + moduleArchive.getModuleName() + ". Restarting the web application...");
                        return true;
                    }
                }
            }

            //check for new exploded modules
            for(File dir : moduleDir.listFiles())
            {
                if(dir.isDirectory())
                {
                    //if this is in the set of ignored dirs, ignore it
                    if (_ignoredExplodedDirs.contains(dir))
                        continue;

                    ExplodedModule explodedModule = new ExplodedModule(dir);
                    if(!_explodedModules.contains(explodedModule))
                    {
                        _log.info("New module directory '" + dir.getPath() + "' found... reloading web application...");
                        return true;
                    }
                }
            }
        }

        //check existing exploded modules
        for(ExplodedModule explodedModule : _explodedModules)
        {
            if(explodedModule.isModified())
            {
                _log.info("Module '" + explodedModule.getRootDirectory().getName() + "' has been modified... reloading web application...");
                return true;
            }

            //if not modified, and there is no source module file
            //redeploy content to the web app so that
            //new static web content, JSP jars, etc are hot-swapped
            if(!explodedModule.getSourceModuleFile().exists())
            {
                try
                {
                    explodedModule.deployToWebApp(_webAppDirectory);
                }
                catch(IOException e)
                {
                    _log.error("Could not hot-swap resources from the module " + explodedModule + ". Restarting web application...");
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Extract .module files
     * @param args see usages
     * @throws ConfigException thrown if there is a problem with the configuration
     * @throws IOException thrown if there is a problem extracting the module archives
     */
    public static void main(String... args) throws ConfigException, IOException
    {
        try
        {
            PipelineBootstrapConfig config = new PipelineBootstrapConfig(args);

            ModuleExtractor extractor = new ModuleExtractor(config.getWebappDir(), new StdOutLogger());
            extractor.extractModules();
        }
        catch (ConfigException e)
        {
            printUsage(e.getMessage());
        }
    }

    private static void printUsage(String error)
    {
        if (error != null)
        {
            System.err.println(error);
            System.err.println();
        }

        System.err.println("java " + ModuleExtractor.class + " [-" + PipelineBootstrapConfig.MODULES_DIR + "=<MODULE_DIR>] [-" + PipelineBootstrapConfig.WEBAPP_DIR + "=<WEBAPP_DIR>] [-" + PipelineBootstrapConfig.CONFIG_DIR + "=<CONFIG_DIR>]");

        System.exit(1);
    }
}
