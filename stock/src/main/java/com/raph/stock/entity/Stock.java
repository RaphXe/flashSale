package com.raph.stock.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stock")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Stock {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "goods_id", nullable = false)
	private Long goodsId;

	@Column(name = "total_stock", nullable = false)
	private Integer totalStock;

	@Column(name = "available_stock", nullable = false)
	private Integer availableStock;

	@Column(name = "locked_stock", nullable = false)
	private Integer lockedStock;

	@Column(name = "update_time", nullable = false)
	private LocalDateTime updateTime;

	@Version
	@Column(name = "version", nullable = false)
	private Integer version;
}
