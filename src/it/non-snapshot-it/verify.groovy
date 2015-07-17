Properties properties = new Properties()
File propertiesFile = new File('target/it/non-snapshot-it/target/app.properties')

propertiesFile.withInputStream {
    properties.load(it)
}

def runtimeString = 'osgi-version'
def val = properties."$runtimeString"

assert val.equals("1.0.2")


