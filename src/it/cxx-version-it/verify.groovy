Properties properties = new Properties()
File propertiesFile = new File('target/it/cxx-version-it/target/app.properties')
System.err.println(propertiesFile.getAbsolutePath())
propertiesFile.withInputStream {
    properties.load(it)
}

def runtimeString = 'osgi-version'
def val = properties."$runtimeString"
println val
def matcher = (val =~ ~/7\.13\.102\.c57_0-SNAPSHOT-v[0-9]*/)
assert matcher.matches()
