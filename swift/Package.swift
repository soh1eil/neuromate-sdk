// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Neuromate",
    platforms: [.macOS(.v12), .iOS(.v15)],
    products: [.library(name: "Neuromate", targets: ["Neuromate"])],
    targets: [
        .target(name: "Neuromate"),
        .executableTarget(name: "RandomMover", dependencies: ["Neuromate"], path: "Examples/RandomMover"),
        .executableTarget(name: "WatcherExample", dependencies: ["Neuromate"], path: "Examples/Watcher"),
        .testTarget(name: "NeuromateTests", dependencies: ["Neuromate"], resources: []),
    ],
    swiftLanguageVersions: [.v5]
)
