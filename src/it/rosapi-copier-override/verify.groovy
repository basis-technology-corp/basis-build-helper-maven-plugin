def path = 'target/it/rosapi-copier-override/target/bundles'

assert(new File(path).listFiles().length == 4)
assert(new File(path, "commons-io-commons-io-2.3.jar").exists())
assert(new File(path, "commons-io-commons-io-2.5.jar").exists())
assert(new File(path, "bundles.xml").exists())
assert(new File(path, "com.google.inject.extensions-guice-throwingproviders-4.0.jar").exists())

def spec = new XmlSlurper().parse(new File(path, "bundles.xml"))
spec.'*'.find { level ->
    if (level.@level == 1) {
        // 2 bundles
        assert level.'*'.size() == 2

        assert level.bundle[0].text() == 'commons-io-commons-io-2.5.jar'
        assert level.bundle[0].@start

        assert level.bundle[1].text() == 'com.google.inject.extensions-guice-throwingproviders-4.0.jar'
        assert level.bundle[1].@start == false
    }

    if (level.@level == 2) {
        assert level.'*'.size() == 1

        assert level.bundle[0].text() == 'commons-io-commons-io-2.3.jar'
        assert level.bundle[0].@start == false
    }
}

assert true


