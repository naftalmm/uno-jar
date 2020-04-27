/*
 * Copyright (c) 2004-2010, P. Simon Tuffs (simon@simontuffs.com)
 * All rights reserved.
 *
 * See the full license at http://one-jar.sourceforge.net/one-jar-license.html
 * This license is also included in the distributions of this software
 * under doc/one-jar-license.txt
 */

/*
 * Many thanks to the following for their contributions to One-Jar:
 *
 * Contributor: Christopher Ottley <xknight@users.sourceforge.net>
 * Contributor: Thijs Sujiten (www.semantica.nl)
 * Contributor: Gerold Friedmann
 */

package com.needhamsoftware.unojar;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.security.AccessController;
import java.security.CodeSource;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Loads classes from pre-defined locations inside the jar file containing this
 * class.  Classes will be loaded from jar files contained in the following
 * locations within the main jar file (on the classpath of the application
 * actually, which when running with the "java -jar" command works out to be
 * the same thing).
 * <ul>
 * <li>
 *   /lib	Used to contain library jars.
 * </li>
 * <li>
 *   /main	Used to contain a default main jar.
 * </li>
 * </ul>
 *
 * @author simon@simontuffs.com (<a href="http://www.simontuffs.com">http://www.simontuffs.com</a>)
 */
public class JarClassLoader extends ClassLoader implements IProperties {

  public final static String PROPERTY_PREFIX = "uno-jar.";
  public final static String P_INFO = PROPERTY_PREFIX + "info";
  public final static String P_VERBOSE = PROPERTY_PREFIX + "verbose";
  public final static String P_SILENT = PROPERTY_PREFIX + "silent";
  public final static String P_JARNAMES = PROPERTY_PREFIX + "jar.names";
  public final static String P_RECORD = PROPERTY_PREFIX + "record";
  // System properties.
  public final static String P_EXPAND_DIR = JarClassLoader.PROPERTY_PREFIX + "expand.dir";
  public final static String P_PATH_SEPARATOR = "|";
  public final static String P_ONE_JAR_CLASS_PATH = JarClassLoader.PROPERTY_PREFIX + "class.path";
  public final static String MANIFEST = "META-INF/MANIFEST.MF";

  public final static String BINLIB_PREFIX = "binlib/";
  public final static String MAIN_PREFIX = "main/";
  public final static String RECORDING = "recording";
  public final static String MULTI_RELEASE = "Multi-Release";
  public final static String CLASS = ".class";

  public final static String NL = System.getProperty("line.separator");

  public final static String JAVA_PROTOCOL_HANDLER = "java.protocol.handler.pkgs";

  protected String name;
  protected boolean noExpand, expanded;
  protected ClassLoader externalClassLoader;

  private static final Logger LOGGER = Logger.getLogger("JarClassLoader");

  protected String oneJarPath;

  private String libPrefix = "lib/";
  public static final Pattern MR_PATTERN = Pattern.compile("META-INF/versions/(\\d+)/");

  public String getOneJarPath() {
    return oneJarPath;
  }

  public void setOneJarPath(String oneJarPath) throws MalformedURLException {
    URL url;
    try {
      url = new URL(oneJarPath);
    } catch (MalformedURLException x) {
      url = new URL("file:" + oneJarPath);
    }
    LOGGER.info("oneJarPath=" + url);
    this.oneJarPath = url.toString();
  }

  static {
    // Add our 'onejar:' protocol handler, but leave open the
    // possibility of a subsequent class taking over the
    // factory.  TODO: (how reasonable is this?)
    String handlerPackage = System.getProperty(JAVA_PROTOCOL_HANDLER);
    if (handlerPackage == null) handlerPackage = "";
    if (handlerPackage.length() > 0) handlerPackage = "|" + handlerPackage;
    handlerPackage = "com.simontuffs" + handlerPackage;
    System.setProperty(JAVA_PROTOCOL_HANDLER, handlerPackage);

  }

  protected String NAME() {
    return (name != null ? "'" + name + "' " : "");
  }

  protected void PRINTLN(String message) {
    System.out.println(message);
  }

  protected void PRINT(String message) {
    System.out.print(message);
  }

  // Synchronize for thread safety.  This is less important until we
  // start to do lazy loading, but it's a good idea anyway.
  protected Map<String,ByteCode> byteCode = Collections.synchronizedMap(new HashMap<>());
  protected Map pdCache = Collections.synchronizedMap(new HashMap());
  protected Map binLibPath = Collections.synchronizedMap(new HashMap());
  protected Set<String> jarNames = Collections.synchronizedSet(new HashSet<>());


  protected boolean record = false, flatten = false, unpackFindResource = false;
  protected String recording = RECORDING;

  protected String jarName, mainJar, wrapDir;
  protected boolean delegateToParent;

  protected static class ByteCode {
    public ByteCode(String name, String original, ByteArrayOutputStream baos, String codebase, Manifest manifest, int mrVersion) {
      this.name = name;
      this.original = original;
      this.bytes = baos.toByteArray();
      this.codebase = codebase;
      this.manifest = manifest;
      this.mrVersion =  mrVersion;
    }

    public byte bytes[];
    public String name, original, codebase;
    public Manifest manifest;
    public int mrVersion;
  }


  /**
   * Create a non-delegating but jar-capable classloader for bootstrap
   * purposes.
   *
   * @param $wrap The directory in the archive from which to load a
   *              wrapping classloader.
   */
  public JarClassLoader(String $wrap) {
    wrapDir = $wrap;
    delegateToParent = wrapDir == null;
    setProperties(this);
    init();
  }

  // this documentation appears to have become out of date
  // TODO: figure out what it was all about...

  /*
   * The main constructor for the Jar-capable classloader.
   * @param $record	If true, the JarClassLoader will record all used classes
   * 					into a recording directory (called 'recording' by default)
   *				 	The name of each jar file will be used as a directory name
   *					for the recorded classes.
   * @param $flatten  Whether to flatten out the recorded classes (i.e. eliminate
   * 					the jar-file name from the recordings).
   *
   * Example: Given the following layout of the uno-jar.jar file
   * <pre>
   *    /
   *    /META-INF
   *    | MANIFEST.MF
   *    /com
   *      /simontuffs
   *        /onejar
   *          Boot.class
   *          JarClassLoader.class
   *    /main
   *        main.jar
   *        /com
   *          /main
   *            Main.class
   *    /lib
   *        util.jar
   *          /com
   *            /util
   *              Util.clas
   * </pre>
   * The recording directory will look like this:
   * <ul>
   * <li>flatten=false</li>
   * <pre>
   *   /recording
   *     /main.jar
   *       /com
   *         /main
   *            Main.class
   *     /util.jar
   *       /com
   *         /util
   *            Util.class
   * </pre>
   *
   * <li>flatten = true</li>
   * <pre>
   *   /recording
   *     /com
   *       /main
   *          Main.class
   *       /util
   *          Util.class
   *
   * </ul>
   * Flatten mode is intended for when you want to create a super-jar which can
   * be launched directly without using uno-jar's launcher.  Run your application
   * under all possible scenarios to collect the actual classes which are loaded,
   * then jar them all up, and point to the main class with a "Main-Class" entry
   * in the manifest.
   *
   */

  /**
   * The main constructor for the Jar-capable classloader.
   *
   * @param parent The parent for this class loader.
   */
  public JarClassLoader(ClassLoader parent) {
    super(parent);
    delegateToParent = true;
    setProperties(this);
    init();
    // System.out.println(PREFIX() + this + " parent=" + parent + " loaded by " + this.getClass().getClassLoader());
  }

  protected static ThreadLocal current = new ThreadLocal();

  /**
   * Common initialization code: establishes a classloader for delegation
   * to uno-jar.class.path resources.
   */
  protected void init() {
    String classpath = System.getProperty(JarClassLoader.P_ONE_JAR_CLASS_PATH);
    if (classpath != null) {
      String tokens[] = classpath.split("\\" + JarClassLoader.P_PATH_SEPARATOR);
      List list = new ArrayList();
      for (int i = 0; i < tokens.length; i++) {
        String path = tokens[i];
        try {
          list.add(new URL(path));
        } catch (MalformedURLException mux) {
          // Try a file:// prefix and an absolute path.
          try {
            String _path = new File(path).getCanonicalPath();
            // URLClassLoader searches in a directory if and only if the path ends with '/':
            // toURI() takes care of adding the trailing slash in this case so everything's ok
            list.add(new File(_path).toURI().toURL());
          } catch (Exception ignore) {
            LOGGER.warning("Unable to parse external path: " + path + ":- " + ignore);
          }
        }
      }
      final URL urls[] = (URL[]) list.toArray(new URL[0]);
      LOGGER.info("external URLs=" + Arrays.asList(urls));
      // BUG-2833948
      // Delegate back into this classloader, use ThreadLocal to avoid recursion.
      externalClassLoader = (URLClassLoader) AccessController.doPrivileged(
          new PrivilegedAction() {
            public Object run() {
              return new URLClassLoader(urls, JarClassLoader.this) {
                // Handle recursion for classes, and mutual recursion for resources.
                final static String LOAD_CLASS = "loadClass():";
                final static String GET_RESOURCE = "getResource():";
                final static String FIND_RESOURCE = "findResource():";

                // Protect entry points which could lead to recursion.  Strangely
                // inelegant because you can't proxy a class.  Or use closures.
                public Class loadClass(String name) throws ClassNotFoundException {
                  if (reentered(LOAD_CLASS + name)) {
                    throw new ClassNotFoundException(name);
                  }
                  LOGGER.fine("externalClassLoader.loadClass(" + name + ")");
                  Object old = current.get();
                  current.set(LOAD_CLASS + name);
                  try {
                    return super.loadClass(name);
                  } finally {
                    current.set(old);
                  }
                }

                public URL getResource(String name) {
                  if (reentered(GET_RESOURCE + name))
                    return null;
                  LOGGER.fine("externalClassLoader.getResource(" + name + ")");
                  Object old = current.get();
                  current.set(GET_RESOURCE + name);
                  try {
                    return super.getResource(name);
                  } finally {
                    current.set(old);
                  }
                }

                public URL findResource(String name) {
                  if (reentered(FIND_RESOURCE + name))
                    return null;
                  LOGGER.fine("externalClassLoader.findResource(" + name + ")");
                  Object old = current.get();
                  current.set(name);
                  try {
                    current.set(FIND_RESOURCE + name);
                    return super.findResource(name);
                  } finally {
                    current.set(old);
                  }
                }

                protected boolean reentered(String name) {
                  // Defend against null name: not sure about semantics there.
                  Object old = current.get();
                  return old != null && old.equals(name);
                }
              };
            }
          });

    }
  }

  public String load(String mainClass) {
    // Hack: if there is a uno-jar.jarname property, use it.
    return load(mainClass, oneJarPath);
  }

  public String load(String mainClass, String jarName) {
    LOGGER.fine("load(" + mainClass + "," + jarName + ")");
    if (record) {
      new File(recording).mkdirs();
    }
    try {
      if (jarName == null) {
        jarName = oneJarPath;
      }
      JarInputStream jis = new JarInputStream(new URL(jarName).openConnection().getInputStream());
      Manifest manifest = jis.getManifest();
      JarEntry entry;
      while ((entry = (JarEntry) jis.getNextEntry()) != null) {
        if (entry.isDirectory())
          continue;

        // The META-INF/MANIFEST.MF file can contain a property which names
        // directories in the JAR to be expanded (comma separated). For example:
        // Uno-Jar-Expand: build,tmp,webapps
        String $entry = entry.getName();
        if (wrapDir != null && $entry.startsWith(wrapDir) || $entry.startsWith(getLibPrefix()) || $entry.startsWith(MAIN_PREFIX)) {
          if (wrapDir != null && !entry.getName().startsWith(wrapDir))
            continue;
          // Load it!
          LOGGER.fine("caching " + $entry);
          LOGGER.fine("using jarFile.getInputStream(" + entry + ")");

          // Note: loadByteCode consumes the input stream, so make sure its scope
          // does not extend beyond here.
          loadByteCode(jis, $entry);

          // Do we need to look for a main class?
          if ($entry.startsWith(MAIN_PREFIX)) {
            if (mainClass == null) {
              JarInputStream mis = new JarInputStream(jis);
              Manifest m = mis.getManifest();
              // Is this a jar file with a manifest?
              if (m != null) {
                mainClass = mis.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
                mainJar = $entry;
              }
            } else if (mainJar != null) {
              LOGGER.warning("A main class is defined in multiple jar files inside " + MAIN_PREFIX + mainJar + " and " + $entry);
              LOGGER.warning("The main class " + mainClass + " from " + mainJar + " will be used");
            }
          }
        } else if ($entry.endsWith(CLASS)) {
          // A plain vanilla class file rooted at the top of the jar file.
          loadBytes(entry, jis, "/", manifest);
          LOGGER.fine("Uno-Jar class: " + jarName + "!/" + entry.getName());
        } else {
          // A resource?
          loadBytes(entry, jis, "/", manifest);
          LOGGER.fine("Uno-Jar resource: " + jarName + "!/" + entry.getName());
        }
      }
      // If mainClass is still not defined, return null.  The caller is then responsible
      // for determining a main class.

    } catch (IOException iox) {
      LOGGER.severe("Unable to load resource: " + iox);
      iox.printStackTrace(System.err);
    }
    return mainClass;
  }

  private String getLibPrefix() {
    return libPrefix;
  }

  public void setLibPrefix(String libPrefix) {
    this.libPrefix = libPrefix;
  }

  public static String replaceProps(Map replace, String string) {
    Pattern pat = Pattern.compile("\\$\\{([^\\}]*)");
    Matcher mat = pat.matcher(string);
    boolean found = mat.find();
    Map props = new HashMap();
    while (found) {
      String prop = mat.group(1);
      props.put(prop, replace.get(prop));
      found = mat.find();
    }
    Iterator iter = props.entrySet().iterator();
    while (iter.hasNext()) {
      Map.Entry entry = (Map.Entry) iter.next();
      string = string.replace("${" + entry.getKey() + "}", (String) entry.getValue());
    }
    return string;
  }

  public static boolean shouldExpand(String expandPaths[], String name) {
    for (int i = 0; i < expandPaths.length; i++) {
      if (name.startsWith(expandPaths[i])) return true;
    }
    return false;
  }

  protected void loadByteCode(InputStream is, String jar) throws IOException {
    JarInputStream jis = new JarInputStream(is);
    JarEntry entry = null;
    // TODO: implement lazy loading of bytecode.
    Manifest manifest = jis.getManifest();
    if (manifest == null) {
      LOGGER.warning("Null manifest from input stream associated with: " + jar);
    }
    while ((entry = jis.getNextJarEntry()) != null) {
      // if (entry.isDirectory()) continue;
      loadBytes(entry, jis, jar, manifest);
    }
    // Add in a fake manifest entry.
    if (manifest != null) {
      entry = new JarEntry(JarClassLoader.MANIFEST);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      manifest.write(baos);
      ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
      loadBytes(entry, bais, jar, manifest);
    }

  }

  protected void loadBytes(JarEntry entry, InputStream is, String jar, Manifest man) throws IOException {
    String entryName = entry.getName();
    int index = entryName.lastIndexOf('.');
    String type = entryName.substring(index + 1);

    // agattung: patch (for One-Jar 0.95)
    // add package handling to avoid NullPointer exceptions
    // after calls to getPackage method of this ClassLoader
    int index2 = entryName.lastIndexOf('/', index - 1);
    if (entryName.endsWith(CLASS) && index2 > -1) {
      String packageName = entryName.substring(0, index2).replace('/', '.');
      if (getPackage(packageName) == null) {
        // Defend against null manifest.
        if (man != null) {
          definePackage(packageName, man, urlFactory.getCodeBase(jar));
        } else {
          definePackage(packageName, null, null, null, null, null, null, null);
        }
      }
    }
    // end patch

    // Because we are doing stream processing, we don't know what
    // the size of the entries is.  So we store them dynamically.
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    copy(is, baos);

    // If entry is a class, check to see that it hasn't been defined
    // already.  Class names must be unique within a classloader because
    // they are cached inside the VM until the classloader is released.
    if (type.equals("class")) {
      if (alreadyCached(entryName, jar, baos)) return;
      cacheBytes(entry, jar, man, entryName, baos);
      LOGGER.fine("cached bytes for class " + entryName);
    } else {
      // https://github.com/nsoft/uno-jar/issues/10 - package names must not end in /
      if (entryName.endsWith(File.separator)) {
        //System.out.println(entryName);
        entryName = entryName.substring(0, entryName.length() - 1);
      }
      // Another kind of resource.  Cache this by name, and also prefixed
      // by the jar name.  Don't duplicate the bytes.  This allows us
      // to map resource lookups to either jar-local, or globally defined.
      String localname = jar + "/" + entryName;
      cacheBytes(entry, jar, man, localname, baos);
      // Keep a set of jar names so we can do multiple-resource lookup by name
      // as in findResources().
      jarNames.add(jar);
      LOGGER.fine("cached bytes for local name " + localname);
      // Only keep the first non-local entry: this is like classpath where the first
      // to define wins.
      if (alreadyCached(entryName, jar, baos)) return;

      cacheBytes(entry, jar, man, entryName, baos);
      LOGGER.fine("cached bytes for entry name " + entryName);

    }
  }

  /**
   * Cache the bytecode or other bytes. Multi-release resources overwrite their original entries.
   *
   * @param entry The JarEntry from which to read bytes
   * @param jar The name of the jar file
   * @param man The manifest from the jar file
   * @param entryName The name of the entry used as a key in the cache
   * @param baos The stream to which bytes have been read.
   */
  private void cacheBytes(JarEntry entry, String jar, Manifest man, String entryName, ByteArrayOutputStream baos) {
    boolean multiRelease = man != null && Boolean.TRUE.toString().equals(man.getMainAttributes().getValue(MULTI_RELEASE));
    if (multiRelease) {
      String jVer = System.getProperty("java.version");
      //noinspection StatementWithEmptyBody
      if (!jVer.startsWith("1.")) {
        // determine the major version of java 9+
        int endIndex = jVer.indexOf('.');
        if (endIndex > 0) {
          jVer = jVer.substring(0, endIndex);
        }
        Matcher m = MR_PATTERN.matcher(entryName);

        if (m.find()) {
          //System.out.println(entryName);
          int mrVer = Integer.parseInt(m.group(1));
          m.reset();
          entryName = m.replaceAll("");
          ByteCode byteCode = this.byteCode.get(entryName);
          if (byteCode != null  ) {
            int oldVer = byteCode.mrVersion;
            if (mrVer > oldVer && mrVer <= Integer.parseInt(jVer)) {
              this.byteCode.put(entryName,new ByteCode(entryName, entry.getName(), baos, jar, man, mrVer));
              return;
            }
          }
        }
      } else {
        // java 8 or earlier, ignore
      }
    }

    byteCode.putIfAbsent(entryName, new ByteCode(entryName, entry.getName(), baos, jar, man, 8));
  }

  /**
   * Override to ensure that this classloader is the thread context classloader
   * when used to load a class.  Avoids subtle, nasty problems.
   */
  public Class loadClass(String name, boolean resolve) throws ClassNotFoundException {
    // Set the context classloader in case any classloaders delegate to it.
    // Otherwise it would default to the sun.misc.Launcher$AppClassLoader which
    // is used to launch the jar application, and attempts to load through
    // it would fail if that code is encapsulated inside the uno-jar.
    if (!isJarClassLoaderAParent(Thread.currentThread().getContextClassLoader())) {
      AccessController.doPrivileged(new PrivilegedAction() {
        public Object run() {
          Thread.currentThread().setContextClassLoader(JarClassLoader.this);
          return null;
        }
      });
    }
    return super.loadClass(name, resolve);
  }

  public boolean isJarClassLoaderAParent(ClassLoader loader) {
    return loader instanceof JarClassLoader
        || loader.getParent() != null && isJarClassLoaderAParent(loader.getParent());
  }

  /**
   * Locate the named class in a jar-file, contained inside the
   * jar file which was used to load <u>this</u> class.
   */
  protected Class findClass(String name) throws ClassNotFoundException {
    // Delegate to external paths first
    Class cls = null;
    if (externalClassLoader != null) {
      try {
        return externalClassLoader.loadClass(name);
      } catch (ClassNotFoundException cnfx) {
        // continue...
      }
    }

    // Make sure not to load duplicate classes.
    cls = findLoadedClass(name);
    if (cls != null) return cls;

    // Look up the class in the byte codes.
    // Translate path?
    LOGGER.fine("findClass(" + name + ")");
    String cache = name.replace('.', '/') + CLASS;
    ByteCode bytecode = (ByteCode) byteCode.get(cache);
    if (bytecode != null) {
      LOGGER.fine("found " + name + " in codebase '" + bytecode.codebase + "'");
      if (record) {
        record(bytecode);
      }
      // Use a protectionDomain to associate the codebase with the
      // class.
      ProtectionDomain pd = (ProtectionDomain) pdCache.get(bytecode.codebase);
      if (pd == null) {
        try {
          URL url = urlFactory.getCodeBase(bytecode.codebase);

          CodeSource source = new CodeSource(url, (Certificate[]) null);
          pd = new ProtectionDomain(source, null, this, null);
          pdCache.put(bytecode.codebase, pd);
        } catch (MalformedURLException mux) {
          throw new ClassNotFoundException(name, mux);
        }
      }

      // Do it the simple way.
      byte bytes[] = bytecode.bytes;

      int i = name.lastIndexOf('.');
      if (i != -1) {
        String pkgname = name.substring(0, i);
        // Check if package already loaded.
        Package pkg = getPackage(pkgname);
        Manifest man = bytecode.manifest;
        if (pkg != null) {
          // Package found, so check package sealing.
          if (pkg.isSealed()) {
            // Verify that code source URL is the same.
            if (!pkg.isSealed(pd.getCodeSource().getLocation())) {
              throw new SecurityException("sealing violation: package " + pkgname + " is sealed");
            }

          } else {
            // Make sure we are not attempting to seal the package
            // at this code source URL.
            if ((man != null) && isSealed(pkgname, man)) {
              throw new SecurityException("sealing violation: can't seal package " + pkgname + ": already loaded");
            }
          }
        } else {
          if (man != null) {
            definePackage(pkgname, man, pd.getCodeSource().getLocation());
          } else {
            definePackage(pkgname, null, null, null, null, null, null, null);
          }
        }
      }

      return defineClass(name, bytes, pd);
    }
    LOGGER.fine(name + " not found");
    throw new ClassNotFoundException(name);

  }

  private boolean isSealed(String name, Manifest man) {
    String path = name.concat("/");
    Attributes attr = man.getAttributes(path);
    String sealed = null;
    if (attr != null) {
      sealed = attr.getValue(Name.SEALED);
    }
    if (sealed == null) {
      if ((attr = man.getMainAttributes()) != null) {
        sealed = attr.getValue(Name.SEALED);
      }
    }
    return "true".equalsIgnoreCase(sealed);
  }

  /**
   * Defines a new package by name in this ClassLoader. The attributes
   * contained in the specified Manifest will be used to obtain package
   * version and sealing information. For sealed packages, the additional URL
   * specifies the code source URL from which the package was loaded.
   *
   * @param name the package name
   * @param man  the Manifest containing package version and sealing
   *             information
   * @param url  the code source url for the package, or null if none
   * @return the newly defined Package object
   * @throws IllegalArgumentException if the package name duplicates an existing package either
   *                                  in this class loader or one of its ancestors
   */
  protected Package definePackage(String name, Manifest man, URL url) throws IllegalArgumentException {
    String path = name.concat("/");
    String specTitle = null, specVersion = null, specVendor = null;
    String implTitle = null, implVersion = null, implVendor = null;
    String sealed = null;
    URL sealBase = null;

    Attributes attr = man.getAttributes(path);
    if (attr != null) {
      specTitle = attr.getValue(Name.SPECIFICATION_TITLE);
      specVersion = attr.getValue(Name.SPECIFICATION_VERSION);
      specVendor = attr.getValue(Name.SPECIFICATION_VENDOR);
      implTitle = attr.getValue(Name.IMPLEMENTATION_TITLE);
      implVersion = attr.getValue(Name.IMPLEMENTATION_VERSION);
      implVendor = attr.getValue(Name.IMPLEMENTATION_VENDOR);
      sealed = attr.getValue(Name.SEALED);
    }
    attr = man.getMainAttributes();
    if (attr != null) {
      if (specTitle == null) {
        specTitle = attr.getValue(Name.SPECIFICATION_TITLE);
      }
      if (specVersion == null) {
        specVersion = attr.getValue(Name.SPECIFICATION_VERSION);
      }
      if (specVendor == null) {
        specVendor = attr.getValue(Name.SPECIFICATION_VENDOR);
      }
      if (implTitle == null) {
        implTitle = attr.getValue(Name.IMPLEMENTATION_TITLE);
      }
      if (implVersion == null) {
        implVersion = attr.getValue(Name.IMPLEMENTATION_VERSION);
      }
      if (implVendor == null) {
        implVendor = attr.getValue(Name.IMPLEMENTATION_VENDOR);
      }
      if (sealed == null) {
        sealed = attr.getValue(Name.SEALED);
      }
    }
    if (sealed != null) {
      boolean isSealed = Boolean.parseBoolean(sealed);
      if (isSealed) {
        sealBase = url;
      }
    }
    return definePackage(name, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, sealBase);
  }

  protected Class defineClass(String name, byte[] bytes, ProtectionDomain pd) throws ClassFormatError {
    // Simple, non wrapped class definition.
    LOGGER.fine("defineClass(" + name + ")");
    return defineClass(name, bytes, 0, bytes.length, pd);
  }

  protected void record(ByteCode bytecode) {
    String fileName = bytecode.original;
    // Write out into the record directory.
    File dir = new File(recording, flatten ? "" : bytecode.codebase);
    File file = new File(dir, fileName);
    if (!file.exists()) {
      file.getParentFile().mkdirs();
      LOGGER.fine("" + file);
      try {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(bytecode.bytes);
        fos.close();

      } catch (IOException iox) {
        LOGGER.severe("unable to record " + file + ": " + iox);
      }

    }
  }

  /**
   * Make a path canonical, removing . and ..
   */
  protected String canon(String path) {
    path = path.replaceAll("/\\./", "/");
    String canon = path;
    String next = canon;
    do {
      next = canon;
      canon = canon.replaceFirst("([^/]*/\\.\\./)", "");
    } while (!next.equals(canon));
    return canon;
  }

  /**
   * Overriden to return resources from the appropriate codebase.
   * There are basically two ways this method will be called: most commonly
   * it will be called through the class of an object which wishes to
   * load a resource, i.e. this.getClass().getResourceAsStream().  Before
   * passing the call to us, java.lang.Class mangles the name.  It
   * converts a file path such as foo/bar/Class.class into a name like foo.bar.Class,
   * and it strips leading '/' characters e.g. converting '/foo' to 'foo'.
   * All of which is a nuisance, since we wish to do a lookup on the original
   * name of the resource as present in the Uno-Jar jar files.
   * The other way is more direct, i.e. this.getClass().getClassLoader().getResourceAsStream().
   * Then we get the name unmangled, and can deal with it directly.
   * <p>
   * The problem is this: if one resource is called /foo/bar/data, and another
   * resource is called /foo.bar.data, both will have the same mangled name,
   * namely 'foo.bar.data' and only one of them will be visible.  Perhaps the
   * best way to deal with this is to store the lookup names in mangled form, and
   * simply issue warnings if collisions occur.  This is not very satisfactory,
   * but is consistent with the somewhat limiting design of the resource name mapping
   * strategy in Java today.
   */
  public InputStream getByteStream(String resource) {

    LOGGER.fine("getByteStream(" + resource + ")");

    InputStream result = null;
    if (externalClassLoader != null) {
      result = externalClassLoader.getResourceAsStream(resource);
    }

    if (result == null) {
      // Delegate to parent classloader first.
      ClassLoader parent = getParent();
      if (parent != null) {
        result = parent.getResourceAsStream(resource);
      }
    }

    if (result == null) {
      // Make resource canonical (remove ., .., etc).
      resource = canon(resource);

      // Look up resolving first.  This allows jar-local
      // resolution to take place.
      ByteCode bytecode = (ByteCode) byteCode.get(resolve(resource));
      if (bytecode == null) {
        // Try again with an unresolved name.
        bytecode = (ByteCode) byteCode.get(resource);
      }
      if (bytecode != null) result = new ByteArrayInputStream(bytecode.bytes);
    }

    // Contributed by SourceForge "ffrog_8" (with thanks, Pierce. T. Wetter III).
    // Handles JPA loading from jars.
    if (result == null) {
      if (jarNames.contains(resource)) {
        // resource wanted is an actual jar
        LOGGER.info("loading resource file directly" + resource);
        result = super.getResourceAsStream(resource);
      }
    }

    // Special case: if we are a wrapping classloader, look up to our
    // parent codebase.  Logic is that the boot JarLoader will have
    // delegateToParent = false, the wrapping classloader will have
    // delegateToParent = true;
    if (result == null && delegateToParent) {
      result = checkParent(resource);
    }
    LOGGER.fine("getByteStream(" + resource + ") -> " + result);
    return result;
  }

  private InputStream checkParent(String resource) {
    InputStream result;// http://code.google.com/p/onejar-maven-plugin/issues/detail?id=16
    ClassLoader parentClassLoader = getParent();

    // JarClassLoader cannot satisfy requests for actual jar files themselves so it must delegate to it's
    // parent. However, the "parent" is not always a JarClassLoader.
    if (parentClassLoader instanceof JarClassLoader) {
      result = ((JarClassLoader) parentClassLoader).getByteStream(resource);
    } else {
      result = parentClassLoader.getResourceAsStream(resource);
    }
    return result;
  }

  /**
   * Resolve a resource name.  Look first in jar-relative, then in global scope.
   *
   * @param $resource
   * @return
   */
  protected String resolve(String $resource) {

    if ($resource.startsWith("/")) $resource = $resource.substring(1);

    String resource = null;
    String caller = getCaller();
    ByteCode callerCode = (ByteCode) byteCode.get(caller);

    if (callerCode != null) {
      // Jar-local first, then global.
      String tmp = callerCode.codebase + "/" + $resource;
      if (byteCode.get(tmp) != null) {
        resource = tmp;
      }
    }
    if (resource == null) {
      // One last try.
      if (byteCode.get($resource) == null) {
        resource = null;
      } else {
        resource = $resource;
      }
    }
    LOGGER.fine("resource " + $resource + " resolved to " + resource + (callerCode != null ? " in codebase " + callerCode.codebase : " (unknown codebase)"));
    return resource;
  }

  protected boolean alreadyCached(String name, String jar, ByteArrayOutputStream baos) {
    // TODO: check resource map to see how we will map requests for this
    // resource from this jar file.  Only a conflict if we are using a
    // global map and the resource is defined by more than
    // one jar file (default is to map to local jar).
    ByteCode existing = (ByteCode) byteCode.get(name);
    if (existing != null) {
      byte[] bytes = baos.toByteArray();
      // If bytecodes are identical, no real problem.  Likewise if it's in
      // META-INF.
      if (!Arrays.equals(existing.bytes, bytes) && !name.startsWith("META-INF")) {
        // TODO: this really needs to be a warning, but there needs to be a way
        // to shut it down.  INFO it for now.  Ideally we need to provide a
        // logging layer (like commons-logging) to allow logging to be delegated.
        if (name.endsWith(".class")) {
          // This is probably trouble.
          LOGGER.warning(existing.name + " in " + jar + " is hidden by " + existing.codebase + " (with different bytecode)");
        } else {
          LOGGER.info(existing.name + " in " + jar + " is hidden by " + existing.codebase + " (with different bytes)");
        }
      } else {
        LOGGER.fine(existing.name + " in " + jar + " is hidden by " + existing.codebase + " (with same bytecode)");
      }
      // Speedup GC.
      bytes = null;
      return true;
    }
    return false;
  }


  protected String getCaller() {

    // TODO: revisit caller determination.
        /*
        StackTraceElement[] stack = new Throwable().getStackTrace();
        // Search upward until we get to a known class, i.e. one with a non-null
        // codebase.  Skip anything in the com.simontuffs.onejar package to avoid
        // classloader classes.
        for (int i=0; i<stack.length; i++) {
            String cls = stack[i].getClassName().replace(".","/") + ".class";
            LOGGER.INFO("getCaller(): cls=" + cls);
            if (byteCode.get(cls) != null) {
                String caller = stack[i].getClassName();
                if (!caller.startsWith("com.simontuffs.onejar")) {
                    return cls;
                }
            }
        }
        */
    return null;
  }

  /**
   * Sets the name of the used  classes recording directory.
   *
   * @param $recording A value of "" will use the current working directory
   *                   (not recommended).  A value of 'null' will use the default directory, which
   *                   is called 'recording' under the launch directory (recommended).
   */
  public void setRecording(String $recording) {
    recording = $recording;
    if (recording == null) recording = RECORDING;
  }

  public String getRecording() {
    return recording;
  }

  public void setRecord(boolean $record) {
    record = $record;
  }

  public boolean getRecord() {
    return record;
  }

  public void setVerbose(boolean verbose) {
    if (verbose) {
      Logger.setLevel(Logger.LOGLEVEL_VERBOSE);
    } else {
      Logger.setLevel(Logger.LOGLEVEL_INFO);
    }
  }

  public void setInfo(boolean info) {
    Logger.setLevel(Logger.LOGLEVEL_INFO);
  }

  public void setSilent(boolean silent) {
    if (silent) {
      Logger.setLevel(Logger.LOGLEVEL_NONE);
    } else {
      Logger.setLevel(Logger.LOGLEVEL_INFO);
    }
  }

  public void setFlatten(boolean $flatten) {
    flatten = $flatten;
  }

  public boolean isFlatten() {
    return flatten;
  }

  protected URLStreamHandler oneJarHandler = new Handler();

  // Injectable URL factory.
  public static interface IURLFactory {
    public URL getURL(String codebase, String resource) throws MalformedURLException;

    public URL getCodeBase(String jar) throws MalformedURLException;
  }

  // Injectable binary library resolver.  E.g suppose you want to place all windows
  // binaries in /binlib/windows, and all redhat-9-i386 binaries in /binlib/redhat/i386/9
  // then you would inject a resolver that checked os.name, os.arch, and os.version,
  // and for redhat-9-i386 returned "redhat/i386/9", for any os.name starting with
  // "windows" returned "windows".
  public static interface IBinlibResolver {
    public String find(String prefix);
  }

  // Resolve URL from codebase and resource.  Allow URL factory to be specified by
  // user of JarClassLoader.

  /**
   * FileURLFactory generates URL's which are resolved relative to the filesystem.
   * These are compatible with frameworks like Spring, but require knowledge of the
   * location of the uno-jar file via unoJarPath.
   */
  public static class FileURLFactory implements IURLFactory {
    JarClassLoader jcl;

    public FileURLFactory(JarClassLoader jcl) {
      this.jcl = jcl;
    }

    public URLStreamHandler jarHandler = new URLStreamHandler() {
      protected URLConnection openConnection(URL url) throws IOException {
        URLConnection connection = new UnoJarURLConnection(url);
        connection.connect();
        return connection;
      }
    };

    // TODO: Unify getURL and getCodeBase, if possible.
    public URL getURL(String codebase, String resource) throws MalformedURLException {
      if (!codebase.equals("/")) {
        codebase = codebase + "!/";
      } else {
        codebase = "";
      }
      String path = jcl.getOneJarPath() + "!/" + codebase + resource;
      URL url = new URL("jar", "", -1, path, jarHandler);
      return url;
    }

    public URL getCodeBase(String jar) throws MalformedURLException {
      ProtectionDomain cd = JarClassLoader.class.getProtectionDomain();
      URL url = cd.getCodeSource().getLocation();
      if (url != null) {
        url = new URL("jar", "", -1, url + "!/" + jar, jarHandler);
      }
      return url;
    }
  }

  /**
   * UnoJarURLFactory generates URL's which are efficient, using the in-memory bytecode
   * to access the resources.
   *
   * @author simon
   */
  @SuppressWarnings("unused") // instantiated by reflection!
  public static class UnoJarURLFactory implements IURLFactory {
    public UnoJarURLFactory(JarClassLoader jcl) {
      // Argument not used.
    }

    public URL getURL(String codebase, String resource) throws MalformedURLException {
      String base = resource.endsWith(".class") ? "" : codebase + "/";
      return new URL(Handler.PROTOCOL + ":/" + base + resource);
    }

    public URL getCodeBase(String jar) throws MalformedURLException {
      return new URL(Handler.PROTOCOL + ":" + jar);
    }
  }

  public URL getResource(String name) {
    // Delegate to external first.
    if (externalClassLoader != null) {
      URL url = externalClassLoader.getResource(name);
      if (url != null)
        return url;
    }
    return super.getResource(name);
  }

  protected IURLFactory urlFactory = new FileURLFactory(this);

  // Allow override for urlFactory
  public void setURLFactory(String urlFactory) throws ClassNotFoundException, IllegalAccessException, InstantiationException, SecurityException, NoSuchMethodException, IllegalArgumentException, InvocationTargetException {
    Class factory = loadClass(urlFactory);
    try {
      // With single JarClassLoader parameter?
      Constructor ctor = factory.getConstructor(new Class[]{JarClassLoader.class});
      this.urlFactory = (IURLFactory) ctor.newInstance(new Object[]{JarClassLoader.this});
    } catch (NoSuchMethodException x) {
      // Default constructor?
      this.urlFactory = (IURLFactory) loadClass(urlFactory).newInstance();
    }
  }

  public IURLFactory getURLFactory() {
    return urlFactory;
  }

  protected IBinlibResolver defaultBinlibResolver = new IBinlibResolver() {
    // Default implementation handles the legacy uno-jar cases.
    public String find(String prefix) {
      final String os = System.getProperty("os.name").toLowerCase();
      final String arch = System.getProperty("os.arch").toLowerCase();

      final String BINLIB_LINUX32_PREFIX = prefix + "linux32/";
      final String BINLIB_LINUX64_PREFIX = prefix + "linux64/";
      final String BINLIB_MACOSX_PREFIX = prefix + "macosx/";
      final String BINLIB_WINDOWS32_PREFIX = prefix + "windows32/";
      final String BINLIB_WINDOWS64_PREFIX = prefix + "windows64/";

      String binlib = null;

      // Mac
      if (os.startsWith("mac os x")) {
        //TODO Nood arch detection on mac
        binlib = BINLIB_MACOSX_PREFIX;
        // Windows
      } else if (os.startsWith("windows")) {
        if (arch.equals("x86")) {
          binlib = BINLIB_WINDOWS32_PREFIX;
        } else {
          binlib = BINLIB_WINDOWS64_PREFIX;
        }
        // So it have to be Linux
      } else {
        if (arch.equals("i386")) {
          binlib = BINLIB_LINUX32_PREFIX;
        } else {
          binlib = BINLIB_LINUX64_PREFIX;
        }
      }
      return binlib;
    }
  };


  protected IBinlibResolver binlibResolver = defaultBinlibResolver;

  // Allow override for urlFactory
  public void setBinlibResolver(String resolver) throws ClassNotFoundException, IllegalAccessException, InstantiationException, SecurityException, NoSuchMethodException, IllegalArgumentException, InvocationTargetException {
    Class cls = loadClass(resolver);
    try {
      // With single JarClassLoader parameter?
      Constructor ctor = cls.getConstructor(new Class[]{JarClassLoader.class});
      this.binlibResolver = (IBinlibResolver) ctor.newInstance(new Object[]{JarClassLoader.this});
    } catch (NoSuchMethodException x) {
      // Default constructor?
      this.binlibResolver = (IBinlibResolver) loadClass(resolver).newInstance();
    }
  }

  public IBinlibResolver getBinlibResolver() {
    return binlibResolver;
  }

  /* (non-Javadoc)
   * @see java.lang.ClassLoader#findResource(java.lang.String)
   */
  // TODO: Revisit the issue of protocol handlers for findResource()
  // and findResources();
  protected URL findResource(String $resource) {
    try {
      LOGGER.fine("findResource(\"" + $resource + "\")");
      URL url = externalClassLoader != null ? externalClassLoader.getResource($resource) : null;
      if (url != null) {
        LOGGER.info("findResource() found in external: \"" + $resource + "\"");
        //LOGGER.VERBOSE("findResource(): " + $resource + "=" + url);
        return url;
      }
      // Delegate to parent.
      ClassLoader parent = getParent();
      if (parent != null) {
        url = parent.getResource($resource);
        if (url != null) {
          return url;
        }
      }
      // Do we have the named resource in our cache?  If so, construct a
      // 'onejar:' URL so that a later attempt to access the resource
      // will be redirected to our Handler class, and thence to this class.
      String resource = resolve($resource);
      if (resource != null) {
        // We know how to handle it.
        ByteCode entry = ((ByteCode) byteCode.get(resource));
        LOGGER.info("findResource() found: \"" + $resource + "\" for caller " + getCaller() + " in codebase " + entry.codebase);
        return urlFactory.getURL(entry.codebase, $resource);
      }
      LOGGER.info("findResource(): unable to locate \"" + $resource + "\"");
      // If all else fails, return null.
      return null;
    } catch (MalformedURLException mux) {
      LOGGER.warning("unable to locate " + $resource + " due to " + mux);
    }
    return null;

  }

  protected Enumeration findResources(String name) throws IOException {
    LOGGER.info("findResources(" + name + ")");
    LOGGER.info("findResources: looking in " + jarNames);
    Iterator iter = jarNames.iterator();
    final List resources = new ArrayList();
    while (iter.hasNext()) {
      String resource = iter.next().toString() + "/" + name;
      ByteCode entry = ((ByteCode) byteCode.get(resource));
      if (byteCode.containsKey(resource)) {
        URL url = urlFactory.getURL(entry.codebase, name);
        LOGGER.info("findResources(): Adding " + url + " to resources list.");
        resources.add(url);
      }
    }
    final Iterator ri = resources.iterator();
    return new Enumeration() {
      public boolean hasMoreElements() {
        return ri.hasNext();
      }

      public Object nextElement() {
        return ri.next();
      }
    };
  }

  /**
   * Utility to assist with copying InputStream to OutputStream.  All
   * bytes are copied, but both streams are left open.
   *
   * @param in  Source of bytes to copy.
   * @param out Destination of bytes to copy.
   * @throws IOException
   */
  protected void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buf = new byte[1024];
    while (true) {
      int len = in.read(buf);
      if (len < 0) break;
      out.write(buf, 0, len);
    }
  }

  public String toString() {
    return super.toString() + (name != null ? "(" + name + ")" : "");
  }

  /**
   * Returns name of the classloader.
   *
   * @return
   */
  public String getName() {
    return name;
  }

  /**
   * Sets name of the classloader.  Default is null.
   *
   * @param string
   */
  public void setName(String string) {
    name = string;
  }

  public void setExpand(boolean expand) {
    noExpand = !expand;
  }

  public boolean isExpanded() {
    return expanded;
  }

  /**
   * Preloader for {@link JarClassLoader#findTheLibrary(String, String)} to allow arch-specific native libraries
   *
   * @param name the (system specific) name of the requested library
   */
  protected String findLibrary(String name) {

    String binlib = binlibResolver.find(BINLIB_PREFIX);
    if (binlibResolver != defaultBinlibResolver && binlib == null)
      binlib = defaultBinlibResolver.find(BINLIB_PREFIX);

    LOGGER.fine("Using arch-specific native library path: " + binlib);

    String retValue = findTheLibrary(binlib, name);
    if (retValue != null) {
      LOGGER.fine("Found in arch-specific directory!");
      return retValue;
    } else {
      LOGGER.fine("Search in standard native directory!");
      return findTheLibrary(BINLIB_PREFIX, name);
    }
  }

  /**
   * If the system specific library exists in the JAR, expand it and return the path
   * to the expanded library to the caller. Otherwise return null so the caller
   * searches the java.library.path for the requested library.
   *
   * @param name          the (system specific) name of the requested library
   * @param BINLIB_PREFIX the (system specific) folder to search in
   * @return the full pathname to the requested library, or null
   * @see Runtime#loadLibrary(String)
   * @since 1.2
   */
  protected String findTheLibrary(String BINLIB_PREFIX, String name) {
    String result = null; // By default, search the java.library.path for it

    String resourcePath = BINLIB_PREFIX + System.mapLibraryName(name);

    // If it isn't in the map, try to expand to temp and return the full path
    // otherwise, remain null so the java.library.path is searched.

    // If it has been expanded already and in the map, return the expanded value
    if (binLibPath.get(resourcePath) != null) {
      result = (String) binLibPath.get(resourcePath);
    } else {

      // See if it's a resource in the JAR that can be extracted
      File tempNativeLib = null;
      FileOutputStream os = null;
      try {
        int lastdot = resourcePath.lastIndexOf('.');
        String suffix = null;
        if (lastdot >= 0) {
          suffix = resourcePath.substring(lastdot);
        }
        InputStream is = this.getClass().getResourceAsStream("/" + resourcePath);

        if (is != null) {
          tempNativeLib = File.createTempFile(name + "-", suffix);
          tempNativeLib.deleteOnExit();
          os = new FileOutputStream(tempNativeLib);
          copy(is, os);
          os.close();
          LOGGER.fine("Stored native library " + name + " at " + tempNativeLib);
          result = tempNativeLib.getPath();
          binLibPath.put(resourcePath, result);
        } else {
          // Library is not in the jar
          // Return null by default to search the java.library.path
          LOGGER.fine("No native library at " + resourcePath +
              "java.library.path will be searched instead.");
        }
      } catch (Throwable e) {
        // Couldn't load the library
        // Return null by default to search the java.library.path
        LOGGER.warning("Unable to load native library: " + e);
      }

    }

    return result;
  }

  protected String getConfirmation(File location) throws IOException {
    String answer = "";
    while (answer == null || (!answer.startsWith("n") && !answer.startsWith("y") && !answer.startsWith("q"))) {
      promptForConfirm(location);
      BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
      answer = br.readLine();
      br.close();
    }
    return answer;
  }

  protected void promptForConfirm(File location) {
    PRINTLN("Do you want to allow '" + oneJarPath + "' to expand files into the file-system at the following location?");
    PRINTLN("  " + location);
    PRINT("Answer y(es) to expand files, n(o) to continue without expanding, or q(uit) to exit: ");
  }

  public void setProperties(IProperties jarloader) {
    LOGGER.info("setProperties(" + jarloader + ")");
    if (JarClassLoader.getProperty(JarClassLoader.P_RECORD)) {
      jarloader.setRecord(true);
      jarloader.setRecording(System.getProperty(JarClassLoader.P_RECORD));
    }
    if (JarClassLoader.getProperty(JarClassLoader.P_JARNAMES)) {
      jarloader.setRecord(true);
      jarloader.setFlatten(false);
    }
    if (JarClassLoader.getProperty(JarClassLoader.P_VERBOSE)) {
      jarloader.setVerbose(true);
    }
    if (JarClassLoader.getProperty(JarClassLoader.P_INFO)) {
      jarloader.setInfo(true);
    }
    if (JarClassLoader.getProperty(JarClassLoader.P_SILENT)) {
      jarloader.setSilent(true);
    }
  }

  public static boolean getProperty(String key) {
    return Boolean.valueOf(System.getProperty(key, "false")).booleanValue();
  }

}
