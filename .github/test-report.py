"""Reports JUnit failures as GitHub annotations.

Log archives are not reachable from the environment this project is
developed in, so a failing test would otherwise be invisible — only the
step name comes back. Annotations do come back, so the failures are
re-emitted as annotations, the same trick used for Kotlin errors.
"""
import glob
import os
import xml.etree.ElementTree as ET


def main():
    reports = glob.glob("app/build/test-results/**/*.xml", recursive=True)
    if not reports:
        print("::error title=tests::No JUnit XML found under app/build/test-results")
        return

    failures = []
    total = 0
    for path in reports:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            total += 1
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                name = "{}.{}".format(case.get("classname", "?"), case.get("name", "?"))
                message = (bad.get("message") or "").strip()
                detail = (bad.text or "").strip().splitlines()
                # The first couple of stack lines are the useful part.
                where = " | ".join(detail[:3])
                failures.append("{}%0A    {}%0A    {}".format(name, message, where))

    if not failures:
        print("::error title=tests::{} tests ran, none reported a failure".format(total))
        return

    # Annotations are capped per step, so failures go out in small batches.
    for i in range(0, len(failures), 4):
        batch = "%0A".join(failures[i:i + 4])
        print("::error title=test-failures-{}::{}".format(i // 4 + 1, batch))
    print("::error title=tests::{} of {} tests failed".format(len(failures), total))


if __name__ == "__main__":
    main()
