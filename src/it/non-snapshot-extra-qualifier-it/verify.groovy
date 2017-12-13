Properties properties = new Properties()
File propertiesFile = new File('target/it/non-snapshot-extra-qualifier-it/target/app.properties')
System.err.println(propertiesFile.getAbsolutePath())
propertiesFile.withInputStream {
    properties.load(it)
}

def runtimeString = 'osgi-version'
def val = properties."$runtimeString"

def matcher = (val =~ ~/1\.0\.2\.qualifier-v[0-9]*/)
assert matcher.matches()


