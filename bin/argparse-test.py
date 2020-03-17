#!/usr/bin/env python

import argparse

class demoCommands:
    @staticmethod
    def demo():
        print("demo() is called")

    @staticmethod
    def connect_demo():
        print("connect_demo() is called")

    @staticmethod
    def destroy_demo():
        print("destroy_demo() is called")


if __name__ == '__main__':
    common_parser = argparse.ArgumentParser(add_help=False)
    common_parser.add_argument("--config", help="Configuration file")

    start_msg = "Demo start message"
    parser = argparse.ArgumentParser(description=start_msg)
    all_parsers = {"default": parser}
    subparsers = parser.add_subparsers(dest="parser")
    for cmd, description in (
            ("demo", "Load and interact with preset demo data."),):
        subparser = subparsers.add_parser(cmd, help=description, parents=[common_parser])
        func = getattr(demoCommands, cmd, None)
        subparser.set_defaults(func=func)
        all_parsers[cmd] = subparser

    # Add demo commands to create, connect, and destroy.
    demo_parser = all_parsers["demo"]
    demo_subparsers = demo_parser.add_subparsers()
    for cmd, description in (
            ("connect", "Connect to the demo database."),
            ("destroy", "Destroy the demo database.")):
        subparser = demo_subparsers.add_parser(cmd, help=description, parents=[common_parser])
        parser_name = cmd + "_demo"
        func = getattr(demoCommands, parser_name, None)
        subparser.set_defaults(func=func)
        all_parsers[parser_name] = subparser

    # import pdb; pdb.set_trace();

    args = parser.parse_args()
    print(args)
    args.func()
