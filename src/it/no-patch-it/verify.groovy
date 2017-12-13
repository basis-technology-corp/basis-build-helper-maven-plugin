Properties properties = new Properties()
File propertiesFile = new File('target/it/no-patch-it/target/app.properties')
System.err.println(propertiesFile.getAbsolutePath())
propertiesFile.withInputStream {
    properties.load(it)
}

def runtimeString = 'osgi-version'
def val = properties."$runtimeString"

def matcher = (val =~ ~/1\.2\.0\.v[0-9]*/)
assert matcher.matches()


