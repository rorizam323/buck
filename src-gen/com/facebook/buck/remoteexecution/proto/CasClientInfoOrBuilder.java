// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: src/com/facebook/buck/remoteexecution/proto/metadata.proto

package com.facebook.buck.remoteexecution.proto;

@javax.annotation.Generated(value="protoc", comments="annotations:CasClientInfoOrBuilder.java.pb.meta")
public interface CasClientInfoOrBuilder extends
    // @@protoc_insertion_point(interface_extends:facebook.remote_execution.CasClientInfo)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * Name of the tool reaching the CAS, eg, buck, worker, engine, ...
   * </pre>
   *
   * <code>string name = 1;</code>
   */
  java.lang.String getName();
  /**
   * <pre>
   * Name of the tool reaching the CAS, eg, buck, worker, engine, ...
   * </pre>
   *
   * <code>string name = 1;</code>
   */
  com.google.protobuf.ByteString
      getNameBytes();
}