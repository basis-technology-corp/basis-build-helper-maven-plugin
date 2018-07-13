assert(new File("target/it/rosapi-copier-override/target/bundles").listFiles().length == 4)
assert(new File("target/it/rosapi-copier-override/target/bundles/commons-io-commons-io-2.3.jar").exists())
assert(new File("target/it/rosapi-copier-override/target/bundles/commons-io-commons-io-2.5.jar").exists())
assert(new File("target/it/rosapi-copier-override/target/bundles/bundles.xml").exists())
assert(new File("target/it/rosapi-copier/target/bundles/com.google.inject.extensions-guice-throwingproviders-4.0.jar").exists())

def spec = new XmlSlurper().parse(new File("target/it/rosapi-copier-override/target/bundles/bundles.xml"))
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


