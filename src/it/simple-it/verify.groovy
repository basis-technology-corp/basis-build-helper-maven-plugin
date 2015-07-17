Properties properties = new Properties()
File propertiesFile = new File('target/app.properties')
propertiesFile.withInputStream {
    properties.load(it)
}

def runtimeString = 'osgi-version'
def val = properties."$runtimeString"

assert val.match("1.0.2.v[0-9]+")


