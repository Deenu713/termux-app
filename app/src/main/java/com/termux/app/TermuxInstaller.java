package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;
import java.io.IOException;
/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (!PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message, MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
													   activity.getString(R.string.bootstrap_error_title),
													   bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError) + "\nTERMUX_FILES_DIR: " + MarkdownUtils.getMarkdownCodeForString(TermuxConstants.TERMUX_FILES_DIR_PATH, false);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
										   activity.getString(R.string.bootstrap_error_title),
										   bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            File[] PREFIX_FILE_LIST =  TERMUX_PREFIX_DIR.listFiles();
            // If prefix directory is empty or only contains the tmp directory
            if(PREFIX_FILE_LIST == null || PREFIX_FILE_LIST.length == 0 || (PREFIX_FILE_LIST.length == 1 && TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH.equals(PREFIX_FILE_LIST[0].getAbsolutePath()))) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains the tmp directory.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
					dialog.dismiss();
					activity.finish();
				})
				.setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
					dialog.dismiss();
					FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
					TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
				}).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
													 title, null, "## " + title + "\n\n" + message + "\n\n" +
													 TermuxUtils.getTermuxDebugMarkdownString(activity),
													 true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
			
            public void run() {
                try {
					
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;
					File supportDir = TermuxConstants.TERMUX_HOME_DIR;
					String sbdir = supportDir.getAbsolutePath() + "/support";
					
					error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    error = FileUtils.clearDirectory("~/support", sbdir);
				
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
																	 "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
																	 true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

					
					
					
				File support = new File(context.getFilesDir().getAbsolutePath() + "/home/support");
					if (!support.exists() && !support.mkdirs()) {
						throw new IOException("Failed to create support  directory");
					}
					File common = new File(context.getFilesDir().getAbsolutePath() + "/home/support/common");
					if (!common.exists() && !common.mkdirs()) {
						throw new IOException("Failed to create common  directory");
					}
					
			
			        //lib_addNonRootUser.sh.so
					File lib_addNonRootUser = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_addNonRootUser.sh.so");
					String nru = lib_addNonRootUser.getAbsolutePath();

					File nruf = new File(support.getAbsolutePath()+ "/addNonRootUser.sh");
					String nrus = nruf.getAbsolutePath();

				    Os.symlink(nru, nrus);	
					
					//lib_arch.so
					File lib_arch = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_arch.so");
					String arm = lib_arch.getAbsolutePath();

					File aarch = new File(support.getAbsolutePath()+ "/arch");
					String arms = aarch.getAbsolutePath();

					Os.symlink(arm, arms);	
					
					//lib_busybox.so		 
					File lib_busybox = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_busybox.so");
					String bu = lib_busybox.getAbsolutePath();

					 File busybox = new File(support.getAbsolutePath()+ "/busybox");
					 String bx = busybox.getAbsolutePath();

					 Os.symlink(bu, bx);	
					 
					//lib_busybox_static.so
					File lib_busybox_static = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_busybox_static.so");
					String bus = lib_busybox_static.getAbsolutePath();

					File busyboxs = new File(common.getAbsolutePath()+ "/busybox_static");
					String bxs = busyboxs.getAbsolutePath();

					Os.symlink(bus, bxs);	
					
					// lib_compressFilesystem.sh.so
					File lib_compressFilesystem = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_compressFilesystem.sh.so");
					String compressFilesystem = lib_compressFilesystem.getAbsolutePath();

					File Filesystem = new File(support.getAbsolutePath()+ "/compressFilesystem.sh");
					String filex = Filesystem.getAbsolutePath();

				    Os.symlink(compressFilesystem, filex);	
					
					//lib_dbclient.so 
					File lib_dbclient = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_dbclient.so");
					String lbs = lib_dbclient.getAbsolutePath();

					File bsl = new File(support.getAbsolutePath()+ "/dbclient");
					String ph = bsl.getAbsolutePath();

				    Os.symlink(lbs, ph);	
					
					//lib_deleteFilesystem.sh.so
					File lib_deleteFilesystem = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_deleteFilesystem.sh.so");
					String dls = lib_deleteFilesystem.getAbsolutePath();

					File deleteFilesystem = new File(support.getAbsolutePath()+ "/deleteFilesystem.sh");
					String dlf = deleteFilesystem.getAbsolutePath();

				    Os.symlink(dls, dlf);	
					
					//lib_execInProot.sh.so
					File lib_execInProot = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_execInProot.sh.so");
					String exe = lib_execInProot.getAbsolutePath();

					File xecInProot = new File(support.getAbsolutePath()+ "/execInProot.sh");
					String xp= xecInProot.getAbsolutePath();

				    Os.symlink(exe, xp);	
					
					//lib_extractFilesystem.sh.so
					File lib_extractFilesystem = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_extractFilesystem.sh.so");
					String fex = lib_extractFilesystem.getAbsolutePath();

					File fes = new File(support.getAbsolutePath()+ "/extractFilesystem.sh");
					String ts = fes.getAbsolutePath();

				    Os.symlink(fex, ts);	
					
					//lib_isServerInProcTree.sh.so
					File lib_isServerInProcTree = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_isServerInProcTree.sh.so");
					String sp = lib_isServerInProcTree.getAbsolutePath();

					File spt = new File(support.getAbsolutePath()+ "/isServerInProcTree.sh");
					String sy = spt.getAbsolutePath();

				    Os.symlink(sp, sy);	
					
					//lib_killProcTree.sh.so 
					File lib_killProcTree = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_killProcTree.sh.so");
					String kpt = lib_killProcTree.getAbsolutePath();

					File kps = new File(support.getAbsolutePath()+ "/killProcTree.sh");
					String bt = kps.getAbsolutePath();

				    Os.symlink(kpt, bt);	
					
					//lib_libc++_shared.so.so 
					File lib_libc = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_libc++_shared.so.so");
					String libs = lib_libc.getAbsolutePath();

					File libcc = new File(support.getAbsolutePath()+ "/libc++_shared.so");
					String kst = libcc.getAbsolutePath();

				    Os.symlink(libs, kst);	
					
			    	//lib_libcrypto.so.1.1.so 
					File lib_libcrypto = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_libcrypto.so.1.1.so");
					String lc = lib_libcrypto.getAbsolutePath();

					File lcr = new File(support.getAbsolutePath()+ "/libcrypto.so.1.1");
					String lo = lcr.getAbsolutePath();

				    Os.symlink(lc, lo);	
					
					//lib_libleveldb.so.1.so
					File lib_libleveldb = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_libleveldb.1.so");
					String lvd = lib_libleveldb.getAbsolutePath();

					File lvdh = new File(support.getAbsolutePath()+ "/libleveldb.so.1");
					String libv = lvdh.getAbsolutePath();

				    Os.symlink(lvd, libv);	
					
					//lib_libtalloc.so.2.a10.so 
					File ib_libtalloc = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_libtalloc.so.2.a10.so");
					String lto = ib_libtalloc.getAbsolutePath();

					File lts = new File(support.getAbsolutePath()+ "/libtalloc.so.2.a10 ");
					String lsk = lts.getAbsolutePath();

				    Os.symlink(lto, lsk);	
					
					//lib_libtalloc.so.2.so
					File lib_libtalloc = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_libtalloc.so.2.so");
					String ta = lib_libtalloc.getAbsolutePath();

					File talloc = new File(support.getAbsolutePath()+ "/libtalloc.so.2");
					String tc = talloc.getAbsolutePath();

				    Os.symlink(ta, tc);	
					
					//lib_libtermux-auth.so.so
					File lib_libtermux = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_libtermux-auth.so.so");
					String lter = lib_libtermux.getAbsolutePath();

					File ltermi = new File(support.getAbsolutePath()+ "/libtermux-auth.so");
					String tez = ltermi.getAbsolutePath();

				    Os.symlink(lter, tez);	
					
					//lib_libutil.so.so
					File lib_libutil = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_libutil.so.so");
					String ut = lib_libutil.getAbsolutePath();

					File util = new File(support.getAbsolutePath()+ "/libutil.so");
					String uts = util.getAbsolutePath();

				    Os.symlink(ut, uts);	
					
					//lib_loader.so
					File lib_loader = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_loader.so");
					String loa = lib_loader.getAbsolutePath();

					File load = new File(support.getAbsolutePath()+ "/loader");
					String lod = load.getAbsolutePath();

				    Os.symlink(loa, lod);	
					
					// lib_loader.a10.so
					File lo1 = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_loader.a10.so");
					String los = lo1.getAbsolutePath();

					File lo2 = new File(support.getAbsolutePath()+ "/loader.a10");
					String los2 = lo2.getAbsolutePath();

				    Os.symlink(los, los2);	
					
					//lib_loader32.a10.so
					File ader32 = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_loader32.a10.so");
					String puader32 = ader32.getAbsolutePath();

					File ader3 = new File(support.getAbsolutePath()+ "/loader32.a10");
					String ader = ader3.getAbsolutePath();

				    Os.symlink(puader32, ader);	
					
					// lib_loader32.so
					File qq = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_loader32.so");
					String qs = qq.getAbsolutePath();

					File qa = new File(support.getAbsolutePath()+ "/loader32");
					String qd = qa.getAbsolutePath();

				    Os.symlink(qs, qd);	
					
					// lib_proot.a10.so
					File ww = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_proot.a10.so");
					String wb = ww.getAbsolutePath();

					File wh = new File(support.getAbsolutePath()+ "/proot.a10");
					String wk = wh.getAbsolutePath();

				    Os.symlink(wb, wk);	
					
					// lib_proot.so
					File lib_proot = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_proot.so");
					String pu = lib_proot.getAbsolutePath();

					File proot = new File(support.getAbsolutePath()+ "/proot");
					String pt = proot.getAbsolutePath();

				    Os.symlink(pu, pt);	
					
					//lib_proot_meta.so
					File lib_prootm = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_proot_meta.so");
					String pum = lib_prootm.getAbsolutePath();

					File prootm = new File(support.getAbsolutePath()+ "/proot_meta");
					String put = prootm.getAbsolutePath();

				    Os.symlink(pum, put);	
					
					//lib_proot_meta_leveldb.so
					File lib_prootml = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_proot_meta_leveldb.so");
					String puml = lib_prootml.getAbsolutePath();

					File prootmls = new File(support.getAbsolutePath()+ "/proot_meta_leveldb");
					String ptml = prootmls.getAbsolutePath();

				    Os.symlink(puml, ptml);	
					
					//lib_stat4.so
					File lib_stat4 = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_stat4.so");
					String stat4 = lib_stat4.getAbsolutePath();

					File st4 = new File(support.getAbsolutePath()+ "/stat4");
					String st4s = st4.getAbsolutePath();

				    Os.symlink(stat4, st4s);	
					
					
					//lib_stat8.so
					File lib_stat8 = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_stat8.so");
					String lib_stat = lib_stat8.getAbsolutePath();

					File lib_st = new File(support.getAbsolutePath()+ "/stat8");
					String lib_s = lib_st.getAbsolutePath();

				    Os.symlink(lib_stat, lib_s);	
					
					//lib_uptime.so
					File lib_uptime = new File(context.getApplicationInfo().nativeLibraryDir  + "/lib_uptime.so");
					String lib_upt = lib_uptime.getAbsolutePath();

					File lib_up = new File(support.getAbsolutePath()+ "/uptime");
					String lib_u = lib_up.getAbsolutePath();

				    Os.symlink(lib_upt, lib_u);	
					
					// libtermux.so
					File libtermux = new File(context.getApplicationInfo().nativeLibraryDir  + "/libtermux.so");
					String termux = libtermux.getAbsolutePath();

					File libterm = new File(support.getAbsolutePath()+ "/libtermux");
					String libt = libterm.getAbsolutePath();

				    Os.symlink(termux,libt);	
		

					

					
			
					
					
                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
																 "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
																 true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }
	
   private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
