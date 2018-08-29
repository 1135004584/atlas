package com.taobao.android.builder.tasks.incremental;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.apkzlib.zip.CentralDirectoryHeader;
import com.android.apkzlib.zip.EncodeUtils;
import com.android.apkzlib.zip.StoredEntry;
import com.android.apkzlib.zip.ZFile;
import com.android.build.api.transform.QualifiedContent.DefaultContentType;
import com.android.build.api.transform.QualifiedContent.Scope;
import com.android.build.gradle.internal.pipeline.OriginalStream;
import com.android.build.gradle.internal.pipeline.StreamFilter;
import com.android.build.gradle.internal.scope.ConventionMappingHelper;
import com.android.build.gradle.internal.scope.TaskConfigAction;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.tasks.IncrementalTask;
import com.android.builder.core.VariantConfiguration;
import com.android.ide.common.res2.FileStatus;
import com.android.utils.FileUtils;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;

/**
 * @author chenhjohn
 * @date 2017/4/24
 */

public class PrepareBaseApkTask extends IncrementalTask {

  // ----- PUBLIC TASK API -----
  private File baseApk;

  private int dexFilesCount;

  private File outputFile;

  private boolean createTPatch;

  private File outputPackage;

  private String versionName;

  // ----- PRIVATE TASK API -----

  @Override
  protected boolean isIncremental() {
    // TODO fix once dep file parsing is resolved.
    return false;
  }

  @Override
  protected void doFullTaskAction() throws IOException {
    File outputFile = getOutputFile();
    FileUtils.deleteIfExists(outputFile);
    File baseApk = getBaseApk();
    ZFile baseApApkZip = new ZFile(baseApk);
    ZFile baseApkZip = new ZFile(outputFile);

    //解压apk
    Predicate<String> predicate;
    boolean patch = isCreateTPatch();
    if (patch) {
      //解压classes.dex文件
      // predicate = s -> !s.endsWith(DOT_DEX);
      predicate = s -> !s.endsWith(SdkConstants.FN_APK_CLASSES_DEX);
    }
    else {
      // 不解压签名文件
      predicate = s -> ("assets/bundleInfo-" + getVersionName() + ".json").equals(s)
                       || "res/drawable/abc_wb_textfield_cdf.jpg".equals(s)
                       || s.startsWith("META-INF/");
    }
    baseApkZip.mergeFrom(baseApApkZip, predicate);

    //向后移动classes.dex文件
    int dexFilesCount = getDexFilesCount();
    if (dexFilesCount > 0) {
      int i;
      if (patch) {
        i = 1;
      }
      else {
        for (i = 1; ; i++) {
          StoredEntry entry = baseApApkZip.get("classes" + (i == 1 ? "" : i) + ".dex");
          if (entry == null) {
            break;
          }
        }
      }
      for (i--; i > 0; i--) {
        String name = "classes" + (i == 1 ? "" : i) + ".dex";
        String to = "classes" + (i + dexFilesCount) + ".dex";
        renameEntry(baseApkZip, name, to);
      }
    }
    baseApkZip.close();
    File outputPackage = getOutputPackage();
    FileUtils.deletePath(outputPackage);
  }

  private static void renameEntry(ZFile zFile, String name, String to) {
    StoredEntry entry = zFile.get(name);
    //entry.delete();
    delete(zFile, entry);
    CentralDirectoryHeader cdh = entry.getCentralDirectoryHeader();
    setName(cdh, to);
    addToEntries(zFile, entry);
  }

  private static Field sName;

  private static Field sEncodedFileName;

  private static Method sAddToEntries;

  private static Method sDelete;

  private static void delete(ZFile baseApkZip, StoredEntry entry) {
    if (sDelete == null) {
      try {
        sDelete = ZFile.class.getDeclaredMethod("delete", StoredEntry.class, boolean.class);
        sDelete.setAccessible(true);
      }
      catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      }
    }
    try {
      sDelete.invoke(baseApkZip, entry, true);
    }
    catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private static void addToEntries(ZFile baseApkZip, StoredEntry entry) {
    if (sAddToEntries == null) {
      try {
        sAddToEntries = ZFile.class.getDeclaredMethod("addToEntries", StoredEntry.class);
        sAddToEntries.setAccessible(true);
      }
      catch (NoSuchMethodException e) {
        throw new RuntimeException(e);
      }
    }
    try {
      sAddToEntries.invoke(baseApkZip, entry);
    }
    catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private static void setName(CentralDirectoryHeader cdh, String name) {
    byte[] mEncodedFileName = EncodeUtils.encode(name, cdh.getGpBit());
    if (sName == null) {
      try {
        sName = CentralDirectoryHeader.class.getDeclaredField("mName");
        sName.setAccessible(true);
      }
      catch (NoSuchFieldException e) {
        throw new RuntimeException(e);
      }
    }
    try {
      sName.set(cdh, name);
    }
    catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
    if (sEncodedFileName == null) {
      try {
        sEncodedFileName = CentralDirectoryHeader.class.getDeclaredField("mEncodedFileName");
        sEncodedFileName.setAccessible(true);
      }
      catch (NoSuchFieldException e) {
        throw new RuntimeException(e);
      }
    }
    try {
      sEncodedFileName.set(cdh, mEncodedFileName);
    }
    catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  @InputFile
  public File getBaseApk() {
    return baseApk;
  }

  public void setBaseApk(File baseApk) {
    this.baseApk = baseApk;
  }

  @Input
  public int getDexFilesCount() {
    return dexFilesCount;
  }

  public void setDexFilesCount(int dexFilesCount) {
    this.dexFilesCount = dexFilesCount;
  }

  @OutputFile
  public File getOutputFile() {
    return outputFile;
  }

  public void setOutputFile(File outputFile) {
    this.outputFile = outputFile;
  }

  @Input
  public boolean isCreateTPatch() {
    return createTPatch;
  }

  public File getOutputPackage() {
    return outputPackage;
  }

  public void setOutputPackage(File outputPackage) {
    this.outputPackage = outputPackage;
  }

  @Input
  // @Optional
  public String getVersionName() {
    return versionName;
  }

  public void setVersionName(String versionName) {
    this.versionName = versionName;
  }

  @Override
  protected void doIncrementalTaskAction(Map<File, FileStatus> changedInputs) throws IOException {

    for (final Map.Entry<File, FileStatus> entry : changedInputs.entrySet()) {
      FileStatus status = entry.getValue();
      switch (status) {
        case NEW:
          break;
        case CHANGED:
          break;
        case REMOVED:
          break;
      }
    }
  }

  public static class ConfigAction implements TaskConfigAction<PrepareBaseApkTask> {
    @NonNull
    private final VariantScope scope;

    private final Supplier<File> baseApk;

    private final Supplier<File> outputFile;

    private final Supplier<Boolean> createTPatch;

    private final Supplier<File> outputPackage;

    public ConfigAction(VariantScope scope, Supplier<File> baseApk, Supplier<File> outputFile,
                        Supplier<Boolean> createTPatch, Supplier<File> outputPackage) {
      this.scope = scope;
      this.baseApk = baseApk;
      this.outputFile = outputFile;
      this.createTPatch = createTPatch;
      this.outputPackage = outputPackage;
    }

    @Override
    @NonNull
    public String getName() {
      return scope.getTaskName("prepare", "BaseApk");
    }

    @Override
    @NonNull
    public Class<PrepareBaseApkTask> getType() {
      return PrepareBaseApkTask.class;
    }

    @Override
    public void execute(@NonNull PrepareBaseApkTask prepareBaseApkTask) {
      final VariantConfiguration<?, ?, ?> variantConfiguration = scope.getVariantConfiguration();

      prepareBaseApkTask.setAndroidBuilder(scope.getGlobalScope().getAndroidBuilder());
      prepareBaseApkTask.setVariantName(scope.getVariantConfiguration().getFullName());
      prepareBaseApkTask.setIncrementalFolder(scope.getIncrementalDir(getName()));
      ConventionMappingHelper.map(prepareBaseApkTask, "baseApk", new Callable<File>() {
        @Override
        public File call() {
          return baseApk.get();
        }
      });
      ConventionMappingHelper.map(prepareBaseApkTask, "dexFilesCount", new Callable<Integer>() {
        @Override
        public Integer call() {
          Set<File> dexFolders = scope.getTransformManager().getPipelineOutput(StreamFilter.DEX).keySet();
          int dexFilesCount = 0;
          // Preconditions.checkState(dexFolders.size() == 1,
          //                          "There must be exactly one output");
          for (File dexFolder : dexFolders) {
            dexFilesCount += scope.getGlobalScope().getProject().fileTree(ImmutableMap.of("dir",
                                                                                          dexFolder,
                                                                                          "includes",
                                                                                          ImmutableList.of("classes*.dex"))).getFiles()
              .size();
          }
          return dexFilesCount;
        }
      });
      ConventionMappingHelper.map(prepareBaseApkTask, "outputFile", new Callable<File>() {
        @Override
        public File call() {
          return outputFile.get();
        }
      });
      // prepareBaseApkTask.createTPatch = createTPatch;
      ConventionMappingHelper.map(prepareBaseApkTask, "createTPatch", new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          return createTPatch.get();
        }
      });

      ConventionMappingHelper.map(prepareBaseApkTask, "outputPackage", new Callable<File>() {
        @Override
        public File call() {
          return outputPackage.get();
        }
      });

      ConventionMappingHelper.map(prepareBaseApkTask, "versionName", variantConfiguration::getVersionName);

      // create the stream generated from this task
      scope.getTransformManager().addStream(OriginalStream.builder().addScope(Scope.PROJECT).addContentType(
        DefaultContentType.RESOURCES).setJars(new Supplier<Collection<File>>() {
        @Override
        public Collection<File> get() {
          return ImmutableList.of(outputFile.get());
        }
      }).build());
    }
  }
}
